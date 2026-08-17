package com.boothlock.boothlock_server.tableqr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "table_session",
        uniqueConstraints = @UniqueConstraint(name = "uq_session_active", columnNames = {"table_id", "ended_at_key"})
)
public class TableSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "table_id", nullable = false)
    private TableEntity table;

    @Column(name = "session_token", nullable = false, unique = true, length = 64)
    private String sessionToken;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt;

    @Column(name = "ended_at_key", nullable = false)
    private long endedAtKey = 0;

    protected TableSessionEntity() {
    }

    public TableSessionEntity(TableEntity table, String sessionToken, LocalDateTime startedAt) {
        this.table = table;
        this.sessionToken = sessionToken;
        this.startedAt = startedAt;
        this.lastActivityAt = startedAt;
    }

    public Long getId() {
        return id;
    }

    public TableEntity getTable() {
        return table;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public LocalDateTime getLastActivityAt() {
        return lastActivityAt;
    }

    public long getEndedAtKey() {
        return endedAtKey;
    }
}
