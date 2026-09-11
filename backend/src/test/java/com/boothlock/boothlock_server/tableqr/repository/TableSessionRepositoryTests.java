package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@Transactional
class TableSessionRepositoryTests {

    @Autowired
    private BoothRepository boothRepository;

    @Autowired
    private TableRepository tableRepository;

    @Autowired
    private TableSessionRepository tableSessionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void endsSessionByRecordingEndedAtAndItsOwnIdAsEndedAtKey() {
        BoothEntity booth = boothRepository.save(new BoothEntity("test booth", "account", null));
        TableEntity table = tableRepository.save(new TableEntity(booth, "A-1", "table-token"));
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, "session-token", LocalDateTime.of(2026, 8, 24, 12, 0))
        );
        entityManager.flush();

        LocalDateTime endedAt = LocalDateTime.of(2026, 8, 24, 13, 0);
        session.end(endedAt);
        entityManager.flush();
        entityManager.clear();

        TableSessionEntity endedSession = tableSessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(endedAt, endedSession.getEndedAt());
        assertEquals(endedSession.getId(), endedSession.getEndedAtKey());
    }

    @Test
    void newSessionKeepsActiveSessionKeyAtZero() {
        BoothEntity booth = boothRepository.save(new BoothEntity("test booth", "account", null));
        TableEntity table = tableRepository.save(new TableEntity(booth, "A-1", "table-token"));
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, "session-token", LocalDateTime.of(2026, 8, 24, 12, 0))
        );

        assertNull(session.getEndedAt());
        assertEquals(0, session.getEndedAtKey());
    }
}
