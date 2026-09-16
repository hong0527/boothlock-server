package com.boothlock.boothlock_server.booth.dto;

public final class BoothInfoDto {

    private BoothInfoDto() {
    }

    /** category·mapX·mapY는 v0.5 신설 — 시딩 전이거나 미설정이면 null */
    public record Response(
            String name,
            String bankAccount,
            String operatingHours,
            long tableCount,
            boolean isOpen,
            String category,
            Integer mapX,
            Integer mapY) {
    }
}
