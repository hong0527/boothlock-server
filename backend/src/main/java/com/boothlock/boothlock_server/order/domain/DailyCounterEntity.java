package com.boothlock.boothlock_server.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** 영업일 채번 카운터 (DB스키마 §1 daily_counter). auto-increment 금지 — FOR UPDATE로 잠그고 +1 (명세서 §2) */
@Entity
@Table(name = "daily_counter")
@IdClass(DailyCounterId.class)
public class DailyCounterEntity {

    @Id
    @Column(name = "booth_id")
    private Long boothId;

    @Id
    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(name = "last_seq", nullable = false)
    private int lastSeq = 0;

    protected DailyCounterEntity() {
    }

    public DailyCounterEntity(Long boothId, LocalDate businessDate) {
        this.boothId = boothId;
        this.businessDate = businessDate;
    }

    /** 채번은 반드시 FOR UPDATE 잠금을 잡은 트랜잭션 안에서만 호출한다 */
    public int nextSeq() {
        lastSeq = lastSeq + 1;
        return lastSeq;
    }

    public Long getBoothId() {
        return boothId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public int getLastSeq() {
        return lastSeq;
    }
}
