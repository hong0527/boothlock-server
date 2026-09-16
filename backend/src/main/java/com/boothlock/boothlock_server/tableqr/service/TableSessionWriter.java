package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.seat.SeatIdlePolicy;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableUnpaidOrderRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
 *   <li>유휴 만료면 그 세션을 종료하고 새로 만든다 — 앞 손님 토큰을 새 손님에게 넘기지 않는다(§1.2 유휴 만료).
 *       종료가 ended_at_key를 자기 id로 바꾼 뒤에 새 세션(ended_at_key=0)을 넣으므로 같은 트랜잭션 안에서 유니크 제약과 부딪히지 않는다</li>
 * </ul>
 * 퇴실과 겹친 스캔은 퇴실이 끝난 뒤 새 세션을 만들어 "세션은 열렸는데 status는 EMPTY"가 남지 않는다.
 * 삭제(soft delete)된 테이블은 잠금 조회 단계에서 404다 — 삭제와 겹친 스캔이 비활성 테이블에 세션을 열지 못한다.
 * 유니크 제약은 이 규율을 거치지 않는 다른 세션 생성 경로를 위한 최후 방어선으로 남는다(DB스키마 원칙 7).
 */
@Component
public class TableSessionWriter {

    private final TableRepository tableRepository;
    private final TableSessionRepository tableSessionRepository;
    private final TableUnpaidOrderRepository tableUnpaidOrderRepository;
    private final SeatIdlePolicy seatIdlePolicy;

    public TableSessionWriter(TableRepository tableRepository,
                              TableSessionRepository tableSessionRepository,
                              TableUnpaidOrderRepository tableUnpaidOrderRepository,
                              SeatIdlePolicy seatIdlePolicy) {
        this.tableRepository = tableRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.tableUnpaidOrderRepository = tableUnpaidOrderRepository;
        this.seatIdlePolicy = seatIdlePolicy;
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
        if (open.isPresent()) {
            TableSessionEntity session = open.get();
            if (isActive(table, session) && tableSessionRepository.touchIfActive(session.getId(), now) == 1) {
                return new Result(session, false);
            }
            // 유휴 만료 — 세션만 종료한다. status는 곧 새 세션이 열리므로 OCCUPIED로 남는다
            tableSessionRepository.endSession(session.getId(), now);
        }

        TableSessionEntity session = tableSessionRepository.saveAndFlush(
                new TableSessionEntity(table, sessionToken, now));
        table.occupy();
        return new Result(session, true);
    }

    private boolean isActive(TableEntity table, TableSessionEntity session) {
        SeatIdlePolicy.Criteria criteria = seatIdlePolicy.criteria();
        return criteria.isActive(session, tableUnpaidOrderRepository.existsUnpaidOrderOn(
                session.getId(), table.getBooth().getId(), criteria.businessDate()));
    }
}
