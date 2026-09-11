package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.dto.AuthenticatedSession;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 세션 토큰 인증 계층 — X-Session-Token 헤더를 sessionId·boothId·tableLabel로 바꿔준다 (C1 후속, C3·C4·C5가 공용으로 씀).
 * sessionToken은 JWT와 달리 클레임이 없는 순수 랜덤값이라, 디코딩이 아니라 DB 조회로만 정보를 얻는다.
 */
@Service
public class TableSessionAuthService {

    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

    private final TableSessionRepository tableSessionRepository;

    public TableSessionAuthService(TableSessionRepository tableSessionRepository) {
        this.tableSessionRepository = tableSessionRepository;
    }

    /** 빈 토큰·존재하지 않는 토큰·이미 종료된 세션 전부 410 — 클라이언트가 할 일은 어느 쪽이든 "QR 재스캔"으로 동일하다 */
    @Transactional
    public AuthenticatedSession authenticate(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new SessionExpiredException();
        }

        TableSessionEntity session = tableSessionRepository.findBySessionTokenWithTableAndBooth(sessionToken)
                .orElseThrow(SessionExpiredException::new);
        if (session.getEndedAt() != null) {
            throw new SessionExpiredException();
        }

        session.touch(LocalDateTime.now(KST_ZONE));

        TableEntity table = session.getTable();
        return new AuthenticatedSession(session.getId(), table.getBooth().getId(), table.getLabel());
    }
}
