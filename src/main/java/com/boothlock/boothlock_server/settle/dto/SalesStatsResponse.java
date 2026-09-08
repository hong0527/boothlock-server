package com.boothlock.boothlock_server.settle.dto;

public record SalesStatsResponse(
        long totalSales,
        ByMethod byMethod,
        long paidOrderCount,
        RefundSummary refundNeeded,
        RefundSummary refunded) {

    public record ByMethod(long BANK_TRANSFER, long CASH) {
    }

    public record RefundSummary(long count, long amount) {
    }
}
