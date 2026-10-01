package com.boothlock.boothlock_server.booth.dto;

public final class BoothInfoDto {

    private BoothInfoDto() {
    }

    /**
     * category·mapX·mapY는 v0.5 신설 — 시딩 전이거나 미설정이면 null.
     * seatFeePerPerson: 자릿세 1인당 금액(명세서 밖, 파일럿) — 0이면 자릿세를 받지 않는다
     * minOrderAmount: 테이블 첫 주문 최소 금액(명세서 밖, 파일럿, 자릿세 제외) — 0이면 제한 없음
     */
    public record Response(
            String name,
            String bankAccount,
            String depositorName,
            String operatingHours,
            long tableCount,
            boolean isOpen,
            String category,
            Integer mapX,
            Integer mapY,
            int seatFeePerPerson,
            int minOrderAmount) {
    }
}
