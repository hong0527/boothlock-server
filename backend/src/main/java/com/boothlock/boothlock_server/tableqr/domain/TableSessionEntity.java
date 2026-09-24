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

import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
// 변경된 컬럼만 UPDATE — 퇴실(O6)과 동시에 들어온 활동 기록(touch)이 전체 컬럼을 쓰면
// 이미 기록된 ended_at·ended_at_key를 NULL·0으로 되돌려 종료된 세션이 되살아난다
@DynamicUpdate
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

    // 손님이 PartySizePage에서 선택한 인원수 — 자릿세(첫 주문에만 서버가 부과) 계산에 쓴다. 미선택이면 NULL(자릿세 미부과)
    @Column(name = "party_size")
    private Integer partySize;

    protected TableSessionEntity() {
    }

    public TableSessionEntity(TableEntity table, String sessionToken, LocalDateTime startedAt) {
        this.table = table;
        this.sessionToken = sessionToken;
        this.startedAt = startedAt;
        this.lastActivityAt = startedAt;
    }

    /**
     * Ends this persisted session and releases the table's active-session slot.
     */
    public void end(LocalDateTime endedAt) {
        if (id == null) {
            throw new IllegalStateException("A session must be persisted before it can be ended.");
        }
        if (endedAtKey != 0) {
            throw new IllegalStateException("A session that has already ended cannot be ended again.");
        }

        this.endedAt = Objects.requireNonNull(endedAt, "endedAt must not be null");
        this.endedAtKey = id;
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

    public Integer getPartySize() {
        return partySize;
    }

    /** C1 세션 복원 — 활성 세션을 다시 찾은 시점을 활동 시각으로 기록한다(폴링도 활동으로 인정) */
    public void touch(LocalDateTime at) {
        this.lastActivityAt = Objects.requireNonNull(at, "at must not be null");
    }

    /** PartySizePage 제출 — 인원수를 저장한다. 세션이 끝났는지는 호출자(TableSessionService)가 조건부 UPDATE로 가른다 */
    public void updatePartySize(int partySize) {
        this.partySize = partySize;
    }
}
