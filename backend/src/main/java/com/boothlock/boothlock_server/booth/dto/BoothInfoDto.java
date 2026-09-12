package com.boothlock.boothlock_server.booth.dto;

public final class BoothInfoDto {

    private BoothInfoDto() {
    }

    public record Response(
            String name,
            String bankAccount,
            String operatingHours,
            long tableCount,
            boolean isOpen) {
    }
}
