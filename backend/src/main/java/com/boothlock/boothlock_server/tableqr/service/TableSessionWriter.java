package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.seat.SeatIdlePolicy;
import com.boothlock.boothlock_server.order.service.OrderWriter;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableCheckoutOrderRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableUnpaidOrderRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 세션 생성 + 테이블 사용중 전환만 담당하는 쓰기 경계 — 별도 빈으로 둔 이유가 있다.
 * 같은 QR 동시 스캔은 활성 세션 유니크 제약(uq_session_active) 위반으로 이 트랜잭션이 롤백되는데,
 * 같은 트랜잭션 안에서는 재조회조차 할 수 없다(OrderWriter와 동일한 이유).
 * 호출자(TableSessionService)가 트랜잭션 밖에서 예외를 받아 새 트랜잭션으로 복구하도록 경계를 여기서 끊는다.
 *
 * <p>테이블 row를 먼저 잠그고(퇴실 O6·삭제도 같은 row를 먼저 잠근다) 잠근 뒤에 열린 세션을 다시 판정한다.
 * <ul>
 *   <li>활성(SeatIdlePolicy)이면 복원 — 동시 스캔의 두 번째 요청은 유니크 위반 대신 첫 요청이 만든 세션을 받는다</li>
 *   <li>유휴 만료면 그 세션을 종료하고(남은 승인대기는 자동 거절) 새로 만든다 — 앞 손님 토큰을 새 손님에게 넘기지 않는다(§1.2 유휴 만료).
 *       종료가 ended_at_key를 자기 id로 바꾼 뒤에 새 세션(ended_at_key=0)을 넣으므로 같은 트랜잭션 안에서 유니크 제약과 부딪히지 않는다</li>
 * </ul>
 * 퇴실과 겹친 스캔은 퇴실이 끝난 뒤 새 세션을 만들어 "세션은 열렸는데 status는 EMPTY"가 남지 않는다.
 * 삭제(soft delete)된 테이블은 잠금 조회 단계에서 404다 — 삭제와 겹친 스캔이 비활성 테이블에 세션을 열지 못한다.
 * 유니크 제약은 이 규율을 거치지 않는 다른 세션 생성 경로를 위한 최후 방어선으로 남는다(DB스키마 원칙 7).
 */
@Component
public class TableSessionWriter {

    // cancel_reason VARCHAR(100)·canceled_by — O6 퇴실의 자동 거절(TableAdminService)과 같은 형식, 사유만 구분한다
    static final String IDLE_AUTO_REJECT_REASON = "유휴 만료 재스캔으로 자동 거절";
    private static final String IDLE_AUTO_REJECT_BY = "SYSTEM";

    private final TableRepository tableRepository;
    private final TableSessionRepository tableSessionRepository;
    private final TableUnpaidOrderRepository tableUnpaidOrderRepository;
    private final TableCheckoutOrderRepository tableCheckoutOrderRepository;
    private final SeatIdlePolicy seatIdlePolicy;
    private final OrderWriter orderWriter;

    public TableSessionWriter(TableRepository tableRepository,
                              TableSessionRepository tableSessionRepository,
                              TableUnpaidOrderRepository tableUnpaidOrderRepository,
                              TableCheckoutOrderRepository tableCheckoutOrderRepository,
                              SeatIdlePolicy seatIdlePolicy,
                              OrderWriter orderWriter) {
        this.tableRepository = tableRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.tableUnpaidOrderRepository = tableUnpaidOrderRepository;
        this.tableCheckoutOrderRepository = tableCheckoutOrderRepository;
        this.seatIdlePolicy = seatIdlePolicy;
        this.orderWriter = orderWriter;
    }

    /** created=false면 잠금을 기다리는 사이 다른 요청이 연 세션을 복원한 것이다 */
    public record Result(TableSessionEntity session, boolean created) {
    }

    @Transactional
    public Result createSession(Long tableId, String sessionToken) {
        TableEntity table = tableRepository.findActiveByIdForUpdate(tableId)
                .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));
        LocalDateTime now = seatIdlePolicy.now();

        Optional<TableSessionEntity> open = tableSessionRepository.findOpenByTableId(tableId);
        Integer handoffPartySize = null;
        if (open.isPresent()) {
            TableSessionEntity session = open.get();
            if (isActive(table, session) && tableSessionRepository.touchIfActive(session.getId(), now) == 1) {
                return new Result(session, false);
            }
            // 유휴 만료 — 세션을 종료한다. status는 곧 새 세션이 열리므로 OCCUPIED로 남는다
            tableSessionRepository.endSession(session.getId(), now);
            // 그 세션의 승인대기(O28)는 O6 퇴실과 같은 조건부 UPDATE로 자동 거절한다(M2) — 종료된 세션의 토큰은 410이라 손님은
            // 더 볼 수 없고, 승인도 종료된 세션이면 409라 남겨 두면 영업일이 바뀔 때까지 "승인 대기" 탭에 매달린다.
            // 입금된 승인대기는 같은 문장에서 REFUND_NEEDED가 된다. 종료 UPDATE가 세션 행을 잠근 뒤라, 진행 중이던 C3 저장은
            // 이미 커밋돼 이 UPDATE에 잡히고 그 뒤 C3는 410이다(퇴실과 같은 순서). 접수(RECEIVED) 주문 처리는 기존 동작 그대로 둔다 —
            // 이미 승인된 주문은 받을 돈·줄 음식이 확정된 것이라 운영자가 주문현황·결제창에서 정리한다(자동 거절 대상이 아니다)
            tableCheckoutOrderRepository.rejectPendingApprovalOrdersOfSessions(List.of(session.getId()),
                    table.getBooth().getId(), IDLE_AUTO_REJECT_REASON, IDLE_AUTO_REJECT_BY, now);
            handoffPartySize = session.getPartySize();
        }

        // 옛 세션의 ended_at과 새 세션의 started_at에 같은 now를 쓴다 — 이 일치가 곧 "유휴 인계"의 표지다
        // (OrderRepository.findIdleHandoffPredecessorIds). O6 퇴실 뒤 스캔은 여기 open이 없어 인계로 잡히지 않는다
        TableSessionEntity session = tableSessionRepository.saveAndFlush(
                new TableSessionEntity(table, sessionToken, now));
        // 앞 세션이 오늘 자릿세를 이미 냈으면 인원수도 이어받는다 — 그래야 C1 응답에 partySize가 실려 프론트가 인원 선택을
        // 다시 띄우지 않고, 새 세션 주문에는 자릿세가 붙지 않는다(OrderWriter.isSeatFeeCharged가 앞 세션 자릿세를 본다).
        // 앞 세션이 자릿세를 안 냈으면(주문 전·취소됨·다른 영업일) 옮기지 않는다 — 옮기면 인원을 묻지 않은 채 옛 인원수로
        // 새로 부과해, 실제로 자리를 바꾼 다른 손님에게 앞 손님 인원만큼 청구할 수 있다
        if (handoffPartySize != null && orderWriter.isSeatFeeCharged(session.getId(), now)) {
            session.updatePartySize(handoffPartySize);
        }
        table.occupy();
        return new Result(session, true);
    }

    private boolean isActive(TableEntity table, TableSessionEntity session) {
        SeatIdlePolicy.Criteria criteria = seatIdlePolicy.criteria();
        return criteria.isActive(session, tableUnpaidOrderRepository.existsUnpaidOrderOn(
                session.getId(), table.getBooth().getId(), criteria.businessDate()));
    }
}
