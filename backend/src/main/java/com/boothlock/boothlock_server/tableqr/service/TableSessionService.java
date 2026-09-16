package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.seat.SeatIdlePolicy;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionCreateRequest;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionResponse;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableUnpaidOrderRepository;
import com.boothlock.boothlock_server.tableqr.support.SecureTokenGenerator;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * C1 세션 발급 — QR 토큰 검증 → 활성 세션 있으면 복원(restored:true), 없으면 생성+테이블 사용중 전환 (명세서 C1).
 * "활성"은 SeatIdlePolicy 정의다(E1·O3와 같음). 종료 안 됐어도 유휴 만료된 세션은 복원하지 않고 종료 후 새로 발급한다 —
 * 그러지 않으면 E1에서 빈자리를 보고 앉은 새 손님이 앞 손님 토큰을 받아 앞 손님 주문을 보고 미결제를 취소할 수 있다.
 * 삭제(soft delete)된 테이블의 QR은 404다 — 운영자 화면(O3)에서 사라진 테이블로 주문이 들어오면 안 된다.
 * 클래스에 @Transactional을 걸지 않는다: 동시 스캔의 유니크 제약 위반 복구가 트랜잭션 밖에서만 가능하기 때문
 * (OrderCreateService와 동일한 이유 — TableSessionWriter 주석 참조).
 */
@Service
public class TableSessionService {

    private final TableRepository tableRepository;
    private final TableSessionRepository tableSessionRepository;
    private final TableSessionWriter tableSessionWriter;
    private final TableUnpaidOrderRepository tableUnpaidOrderRepository;
    private final SeatIdlePolicy seatIdlePolicy;

    public TableSessionService(TableRepository tableRepository,
                                TableSessionRepository tableSessionRepository,
                                TableSessionWriter tableSessionWriter,
                                TableUnpaidOrderRepository tableUnpaidOrderRepository,
                                SeatIdlePolicy seatIdlePolicy) {
        this.tableRepository = tableRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.tableSessionWriter = tableSessionWriter;
        this.tableUnpaidOrderRepository = tableUnpaidOrderRepository;
        this.seatIdlePolicy = seatIdlePolicy;
    }

    public TableSessionResponse createOrRestore(TableSessionCreateRequest request) {
        if (request == null || request.tableToken() == null || request.tableToken().isBlank()) {
            throw new InvalidRequestException("tableToken이 필요합니다.");
        }

        // booth를 함께 가져온다 — 응답 조립이 이 트랜잭션 밖(메서드 종료 후)에서 이뤄져 LAZY 접근이 불가능하다.
        // 토큰은 바이트 단위로 같아야 한다 — DB 비교(=)는 콜레이션을 따라 MySQL 기본 ai_ci면 대소문자를, utf8mb4_bin이면 끝 공백을
        // 무시해 틀린 토큰이 조회된다(운영 스키마 SQL은 bin, Hibernate가 만든 스키마는 ai_ci). 조회 뒤 equals로 한 번 더 대조한다
        TableEntity table = tableRepository.findActiveByTableTokenWithBooth(request.tableToken())
                .filter(found -> found.getTableToken().equals(request.tableToken()))
                .orElseThrow(() -> new NotFoundException("유효하지 않은 QR입니다."));

        TableSessionEntity restored = restoreActiveSession(table.getId(), table.getBooth().getId());
        if (restored != null) {
            return toResponse(table, restored, true);
        }

        // 열린 세션이 없거나 유휴 만료다 — 종료·생성은 테이블 row를 잠근 쓰기 경계에서 다시 판정하고 처리한다
        String sessionToken = SecureTokenGenerator.generate();
        try {
            TableSessionWriter.Result result = tableSessionWriter.createSession(table.getId(), sessionToken);
            return toResponse(table, result.session(), !result.created());
        } catch (DataIntegrityViolationException e) {
            // 동시 스캔 레이스 — 저장 트랜잭션이 끝난 뒤라 여기서는 재조회가 안전하다 (DB스키마 §1 table_session 주석)
            TableSessionEntity winner = restoreActiveSession(table.getId(), table.getBooth().getId());
            if (winner != null) {
                return toResponse(table, winner, true);
            }
            throw e;
        }
    }

    /**
     * 열린 세션을 찾아 활동 시각을 조건부 UPDATE로 갱신한다. 찾은 뒤 갱신 전에 퇴실(O6)이 커밋되면 0건이라 복원하지 않는다 —
     * 엔티티를 고쳐 save(merge)하면 옛 사본의 ended_at=NULL이 덮어써져 방금 종료된 세션이 되살아난다
     */
    private TableSessionEntity restoreActiveSession(Long tableId, Long boothId) {
        SeatIdlePolicy.Criteria criteria = seatIdlePolicy.criteria();
        return tableSessionRepository.findOpenByTableId(tableId)
                .filter(session -> criteria.isActive(session, tableUnpaidOrderRepository.existsUnpaidOrderOn(
                        session.getId(), boothId, criteria.businessDate())))
                .filter(session -> tableSessionRepository.touchIfActive(session.getId(), seatIdlePolicy.now()) == 1)
                .orElse(null);
    }

    private TableSessionResponse toResponse(TableEntity table, TableSessionEntity session, boolean restored) {
        return new TableSessionResponse(
                session.getSessionToken(),
                new TableSessionResponse.Booth(table.getBooth().getName(), table.getBooth().isOpen()),
                new TableSessionResponse.Table(table.getLabel()),
                restored);
    }
}
