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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TableSessionAuthServiceTests {

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
                new TableSessionEntity(table, "session-token-1", LocalDateTime.now().minusMinutes(5)));

        AuthenticatedSession result = tableSessionAuthService.authenticate("session-token-1");

        assertEquals(session.getId(), result.sessionId());
        assertEquals(booth.getId(), result.boothId());
        assertEquals("A-1", result.tableLabel());
    }

    @Test
    void touchesLastActivityAtOnAuthenticate() {
        LocalDateTime startedAt = LocalDateTime.now().minusMinutes(10);
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

    @Test
    void rejectsBlankTokenWithSessionExpired() {
        assertThrows(SessionExpiredException.class, () -> tableSessionAuthService.authenticate(""));
        assertThrows(SessionExpiredException.class, () -> tableSessionAuthService.authenticate(null));
    }

    @Test
    void rejectsEndedSessionWithSessionExpired() {
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, "session-token-3", LocalDateTime.now().minusMinutes(30)));
        session.end(LocalDateTime.now());
        tableSessionRepository.save(session);

        assertThrows(SessionExpiredException.class, () -> tableSessionAuthService.authenticate("session-token-3"));
    }
}
