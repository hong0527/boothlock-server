package com.boothlock.boothlock_server.booth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "staff_account")
public class StaffAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booth_id")
    private BoothEntity booth;

    @Column(name = "login_id", nullable = false, unique = true, length = 50)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "password_changed_at", nullable = false)
    private LocalDateTime passwordChangedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StaffRole role;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    protected StaffAccountEntity() {
    }

    public StaffAccountEntity(
            BoothEntity booth,
            String loginId,
            String passwordHash,
            LocalDateTime passwordChangedAt,
            StaffRole role
    ) {
        this.booth = booth;
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.passwordChangedAt = passwordChangedAt;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public BoothEntity getBooth() {
        return booth;
    }

    public String getLoginId() {
        return loginId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public LocalDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public StaffRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public boolean isLockedAt(LocalDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public long recordLoginFailure(LocalDateTime now) {
        failedLoginCount++;
        if (failedLoginCount < 5) {
            return 0;
        }

        long lockSeconds = Math.min(30L << Math.min(failedLoginCount - 5, 5), 600L);
        lockedUntil = now.plusSeconds(lockSeconds);
        return lockSeconds;
    }

    public void resetLoginFailures() {
        failedLoginCount = 0;
        lockedUntil = null;
    }
}
