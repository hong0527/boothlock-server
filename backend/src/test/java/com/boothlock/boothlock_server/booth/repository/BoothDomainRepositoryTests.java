package com.boothlock.boothlock_server.booth.repository;

import com.boothlock.boothlock_server.booth.domain.BoothAccountChangeLogEntity;
import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class BoothDomainRepositoryTests {

    @Autowired
    private BoothRepository boothRepository;

    @Autowired
    private StaffAccountRepository staffAccountRepository;

    @Autowired
    private BoothAccountChangeLogRepository changeLogRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesBoothAndFindsStaffByLoginId() {
        BoothEntity booth = boothRepository.save(
                new BoothEntity("테스트 부스", "테스트용 계좌", "18:00~02:00")
        );
        LocalDateTime passwordChangedAt = LocalDateTime.of(2026, 8, 8, 18, 0);
        staffAccountRepository.save(new StaffAccountEntity(
                booth,
                "test-admin",
                "{bcrypt}test-password-hash",
                passwordChangedAt,
                StaffRole.ADMIN
        ));

        entityManager.flush();
        entityManager.clear();

        StaffAccountEntity saved = staffAccountRepository.findByLoginId("test-admin").orElseThrow();
        assertNotNull(saved.getId());
        assertEquals(booth.getId(), saved.getBooth().getId());
        assertEquals(StaffRole.ADMIN, saved.getRole());
        assertEquals(passwordChangedAt, saved.getPasswordChangedAt());
        assertTrue(saved.isActive());
        assertEquals(0, saved.getFailedLoginCount());
        assertNull(saved.getLockedUntil());
        assertTrue(staffAccountRepository.existsByLoginId("test-admin"));
    }

    @Test
    void allowsSuperAdminWithoutBooth() {
        staffAccountRepository.save(new StaffAccountEntity(
                null,
                "test-super-admin",
                "{bcrypt}test-password-hash",
                LocalDateTime.of(2026, 8, 8, 18, 0),
                StaffRole.SUPER_ADMIN
        ));

        entityManager.flush();
        entityManager.clear();

        StaffAccountEntity saved = staffAccountRepository.findByLoginId("test-super-admin").orElseThrow();
        assertNull(saved.getBooth());
        assertEquals(StaffRole.SUPER_ADMIN, saved.getRole());
    }

    @Test
    void savesBoothAccountChangeLog() {
        BoothEntity booth = boothRepository.save(
                new BoothEntity("테스트 부스", "변경 전 계좌", null)
        );
        LocalDateTime changedAt = LocalDateTime.of(2026, 8, 8, 19, 0);
        BoothAccountChangeLogEntity changeLog = changeLogRepository.save(
                new BoothAccountChangeLogEntity(
                        booth,
                        "test-admin",
                        changedAt,
                        "변경 전 계좌",
                        "변경 후 계좌"
                )
        );

        entityManager.flush();
        entityManager.clear();

        BoothAccountChangeLogEntity saved = changeLogRepository.findById(changeLog.getId()).orElseThrow();
        assertEquals(booth.getId(), saved.getBooth().getId());
        assertEquals("test-admin", saved.getChangedBy());
        assertEquals(changedAt, saved.getChangedAt());
        assertEquals("변경 전 계좌", saved.getOldValue());
        assertEquals("변경 후 계좌", saved.getNewValue());
    }
}
