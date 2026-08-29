package com.boothlock.boothlock_server.order.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** daily_counter 복합 PK (booth_id, business_date) — 필드명은 엔티티와 동일해야 한다 */
public class DailyCounterId implements Serializable {

    private Long boothId;
    private LocalDate businessDate;

    public DailyCounterId() {
    }

    public DailyCounterId(Long boothId, LocalDate businessDate) {
        this.boothId = boothId;
        this.businessDate = businessDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DailyCounterId that)) {
            return false;
        }
        return Objects.equals(boothId, that.boothId)
                && Objects.equals(businessDate, that.businessDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(boothId, businessDate);
    }
}
