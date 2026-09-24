package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.global.seat.SeatIdlePolicy;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.dto.AuthenticatedSession;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableUnpaidOrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션 토큰 인증 계층 — X-Session-Token 헤더를 sessionId·boothId·tableLabel로 바꿔준다 (C1 후속, C3·C4·C5가 공용으로 씀).
 * sessionToken은 JWT와 달리 클레임이 없는 순수 랜덤값이라, 디코딩이 아니라 DB 조회로만 정보를 얻는다.
 *
 * <p><b>유휴 세션 거절 (명세서 v0.6 §1.2에서 바뀐 점)</b> — v0.6은 "종료되지 않았으면 유휴여도 통과"였다. 그런데 통과할 때마다
 * last_activity_at을 갱신하므로, SeatIdlePolicy가 이미 비활성으로 본 세션도 어제 손님 폰의 폴링 한 번이면 다시 활성이 된다.
 * 그 사이 새 손님이 QR을 찍으면 C1이 그 세션을 복원(restored:true)해 넘겨주고, 새 손님이 앞 손님 주문을 보고 취소까지 할 수 있다.
 * 그래서 이제는 SeatIdlePolicy 기준 비활성(유휴이고 현재 영업일 미결제도 없음)이면 410이다. 판정은 C1·O3와 같은
 * {@link SeatIdlePolicy.Criteria#isActive}를 그대로 불러 세 곳이 어긋날 수 없게 한다.
 * 유휴라도 현재 영업일 미결제가 있으면 v0.6대로 통과한다 — "미결제 보유 세션은 영업일 종료로만 만료"(§7-9)라
 * C1도 그 세션을 복원하므로 손님이 결제 안내를 계속 볼 수 있어야 한다.
 * 여기서 세션을 종료하지는 않는다 — 410 예외가 이 트랜잭션을 롤백하므로 종료 UPDATE도 함께 사라진다.
 * 종료는 다음 C1 스캔(TableSessionWriter.createSession)이 테이블 행을 잠근 뒤 같은 판정으로 하고 새 세션을 발급한다.
 */
@Service
public class TableSessionAuthService {

    private final TableSessionRepository tableSessionRepository;
    private final TableUnpaidOrderRepository tableUnpaidOrderRepository;
    private final SeatIdlePolicy seatIdlePolicy;

    public TableSessionAuthService(TableSessionRepository tableSessionRepository,
                                   TableUnpaidOrderRepository tableUnpaidOrderRepository,
                                   SeatIdlePolicy seatIdlePolicy) {
        this.tableSessionRepository = tableSessionRepository;
        this.tableUnpaidOrderRepository = tableUnpaidOrderRepository;
        this.seatIdlePolicy = seatIdlePolicy;
    }

    /**
     * 빈 토큰·존재하지 않는 토큰·이미 종료된 세션·유휴 만료 세션(SeatIdlePolicy 비활성) 전부 410 —
     * 클라이언트가 할 일은 어느 쪽이든 "QR 재스캔"으로 동일하다
     */
    @Transactional
    public AuthenticatedSession authenticate(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new SessionExpiredException();
        }

        // DB 비교(=)는 콜레이션을 따른다 — MySQL 기본 ai_ci면 대소문자를, utf8mb4_bin이면 끝 공백을 무시해 틀린 토큰이 조회된다.
        // 인증값이라 조회 뒤 equals로 바이트 단위로 한 번 더 대조한다 (TableSessionService의 tableToken과 같은 방어)
        TableSessionEntity session = tableSessionRepository.findBySessionTokenWithTableAndBooth(sessionToken)
                .filter(found -> found.getSessionToken().equals(sessionToken))
                .orElseThrow(SessionExpiredException::new);
        if (session.getEndedAt() != null) {
            throw new SessionExpiredException();
        }
        // 활동 시각을 갱신하기 전에 판정한다 — 갱신 뒤에 보면 방금 쓴 시각 때문에 항상 활성이다.
        // 미결제 조회는 유휴일 때만 한다(|| 단축 평가와 같은 결과) — 폴링마다 주문 조회가 붙지 않게
        SeatIdlePolicy.Criteria criteria = seatIdlePolicy.criteria();
        boolean idle = !criteria.isActive(session, false);
        if (idle && !criteria.isActive(session, tableUnpaidOrderRepository.existsUnpaidOrderOn(
                session.getId(), session.getTable().getBooth().getId(), criteria.businessDate()))) {
            throw new SessionExpiredException();
        }

        // 조건부 UPDATE — 조회와 갱신 사이에 퇴실(O6)이 커밋됐으면 0건이고 410이다.
        // 엔티티 touch(더티 체킹)로 두면 퇴실 직후에도 이번 요청은 통과하고, 전체 컬럼 UPDATE였다면 세션을 되살리기까지 했다.
        // 시각은 유휴 정책과 같은 시계로 기록한다 — 판정 기준(idleSince)과 활동 시각의 시계가 다르면 경계가 어긋난다
        if (tableSessionRepository.touchIfActive(session.getId(), seatIdlePolicy.now()) == 0) {
            throw new SessionExpiredException();
        }

        TableEntity table = session.getTable();
        return new AuthenticatedSession(session.getId(), table.getBooth().getId(), table.getLabel(), session.getPartySize());
    }
}
