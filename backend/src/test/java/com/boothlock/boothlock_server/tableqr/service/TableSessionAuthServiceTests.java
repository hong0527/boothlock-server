package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.dto.AuthenticatedSession;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TableSessionAuthServiceTests {

    /** 프로덕션 코드가 KST로 시각을 만든다 — 시딩도 같은 기준이어야 build.gradle의 시간대 고정에 기대지 않는다 */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired TableSessionAuthService tableSessionAuthService;
    @Autowired BoothRepository boothRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;

    private BoothEntity booth;
    private TableEntity table;

    @BeforeEach
    void setUp() {
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        boothRepository.deleteAll();

        booth = boothRepository.save(new BoothEntity("인증 부스", "은행 1234", null));
        table = tableRepository.save(new TableEntity(booth, "A-1", "table-token-1"));
    }

    @AfterEach
    void tearDown() {
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void resolvesSessionIdBoothIdAndTableLabel() {
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, "session-token-1", LocalDateTime.now(KST).minusMinutes(5)));

        AuthenticatedSession result = tableSessionAuthService.authenticate("session-token-1");

        assertEquals(session.getId(), result.sessionId());
        assertEquals(booth.getId(), result.boothId());
        assertEquals("A-1", result.tableLabel());
    }

    @Test
    void touchesLastActivityAtOnAuthenticate() {
        LocalDateTime startedAt = LocalDateTime.now(KST).minusMinutes(10);
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, "session-token-2", startedAt));

        tableSessionAuthService.authenticate("session-token-2");

        TableSessionEntity reloaded = tableSessionRepository.findById(session.getId()).orElseThrow();
        assertTrue(reloaded.getLastActivityAt().isAfter(startedAt));
    }

    @Test
    void rejectsUnknownTokenWithSessionExpired() {
        assertThrows(SessionExpiredException.class, () -> tableSessionAuthService.authenticate("no-such-token"));
    }

    /**
     * 토큰은 바이트 단위로 같아야 한다 — 대소문자만 다른 토큰·끝에 공백이 붙은 토큰은 410이고 활동 시각도 갱신되지 않는다.
     * H2는 대소문자를 구분하지만 MySQL은 콜레이션이 ai_ci면 대소문자를·utf8mb4_bin이면 끝 공백을 무시해 틀린 토큰이 조회된다 —
     * 서비스가 조회 뒤 equals로 대조하는지 확인한다(MySQL에서 돌리면 그 방어가 없을 때 이 테스트가 깨진다)
     */
    @Test
    void rejectsTokenDifferingOnlyByCaseOrTrailingSpace() {
        // 컬럼 정밀도(마이크로초)에 맞춰 잘라 둔다 — 재조회 값과 그대로 비교하기 위해
        LocalDateTime startedAt = LocalDateTime.now(KST).minusMinutes(10).truncatedTo(ChronoUnit.MICROS);
        TableSessionEntity session = tableSessionRepository.save(new TableSessionEntity(table, "Session-Token-4", startedAt));

        for (String wrong : new String[] {"session-token-4", "SESSION-TOKEN-4", "Session-Token-4 "}) {
            assertThrows(SessionExpiredException.class, () -> tableSessionAuthService.authenticate(wrong), wrong);
        }
        assertEquals(startedAt, tableSessionRepository.findById(session.getId()).orElseThrow().getLastActivityAt(),
                "틀린 토큰이 활동 시각을 갱신했다");
        assertEquals(session.getId(), tableSessionAuthService.authenticate("Session-Token-4").sessionId());
    }

    @Test
    void rejectsBlankTokenWithSessionExpired() {
        assertThrows(SessionExpiredException.class, () -> tableSessionAuthService.authenticate(""));
        assertThrows(SessionExpiredException.class, () -> tableSessionAuthService.authenticate(null));
    }

    @Test
    void rejectsEndedSessionWithSessionExpired() {
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, "session-token-3", LocalDateTime.now(KST).minusMinutes(30)));
        session.end(LocalDateTime.now(KST));
        tableSessionRepository.save(session);

        assertThrows(SessionExpiredException.class, () -> tableSessionAuthService.authenticate("session-token-3"));
    }
}
