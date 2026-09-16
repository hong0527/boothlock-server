package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.global.seat.SeatIdlePolicy;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.dto.AuthenticatedSession;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션 토큰 인증 계층 — X-Session-Token 헤더를 sessionId·boothId·tableLabel로 바꿔준다 (C1 후속, C3·C4·C5가 공용으로 씀).
 * sessionToken은 JWT와 달리 클레임이 없는 순수 랜덤값이라, 디코딩이 아니라 DB 조회로만 정보를 얻는다.
 */
@Service
public class TableSessionAuthService {

    private final TableSessionRepository tableSessionRepository;
    private final SeatIdlePolicy seatIdlePolicy;

    public TableSessionAuthService(TableSessionRepository tableSessionRepository, SeatIdlePolicy seatIdlePolicy) {
        this.tableSessionRepository = tableSessionRepository;
        this.seatIdlePolicy = seatIdlePolicy;
    }

    /** 빈 토큰·존재하지 않는 토큰·이미 종료된 세션 전부 410 — 클라이언트가 할 일은 어느 쪽이든 "QR 재스캔"으로 동일하다 */
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

        // 조건부 UPDATE — 조회와 갱신 사이에 퇴실(O6)이 커밋됐으면 0건이고 410이다.
        // 엔티티 touch(더티 체킹)로 두면 퇴실 직후에도 이번 요청은 통과하고, 전체 컬럼 UPDATE였다면 세션을 되살리기까지 했다.
        // 시각은 유휴 정책과 같은 시계로 기록한다 — 판정 기준(idleSince)과 활동 시각의 시계가 다르면 경계가 어긋난다
        if (tableSessionRepository.touchIfActive(session.getId(), seatIdlePolicy.now()) == 0) {
            throw new SessionExpiredException();
        }

        TableEntity table = session.getTable();
        return new AuthenticatedSession(session.getId(), table.getBooth().getId(), table.getLabel());
    }
}
