package com.boothlock.boothlock_server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "booth_account_change_log")
public class BoothAccountChangeLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booth_id", nullable = false)
    private BoothEntity booth;

    @Column(name = "changed_by", nullable = false, length = 50)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "old_value", nullable = false, length = 100)
    private String oldValue;

    @Column(name = "new_value", nullable = false, length = 100)
    private String newValue;

    protected BoothAccountChangeLogEntity() {
    }

    public BoothAccountChangeLogEntity(
            BoothEntity booth,
            String changedBy,
            LocalDateTime changedAt,
            String oldValue,
            String newValue
    ) {
        this.booth = booth;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public Long getId() {
        return id;
    }

    public BoothEntity getBooth() {
        return booth;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }
}
