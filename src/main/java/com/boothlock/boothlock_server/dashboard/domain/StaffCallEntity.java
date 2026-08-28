package com.boothlock.boothlock_server.dashboard.domain;

import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;

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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** 직원 호출 (DB스키마 §staff_call — `CALL`은 SQL 예약어라 테이블명 staff_call) */
@Entity
@Table(name = "staff_call")
public class StaffCallEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private TableSessionEntity session;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private CallReason reason;

    // 대시보드(O10)는 이 값이 false인 것만 노출한다 (DB스키마 §staff_call)
    @Column(nullable = false)
    private boolean acked = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected StaffCallEntity() {
    }

    public StaffCallEntity(TableSessionEntity session, CallReason reason, LocalDateTime createdAt) {
        this.session = session;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public TableSessionEntity getSession() {
        return session;
    }

    public CallReason getReason() {
        return reason;
    }

    public boolean isAcked() {
        return acked;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
