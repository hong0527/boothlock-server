package com.boothlock.boothlock_server.settle.dto;

import com.boothlock.boothlock_server.order.domain.OrderItemType;

import java.util.List;

public record SalesStatsResponse(
        String businessDate,
        long totalSales,
        ByMethod byMethod,
        long paidOrderCount,
        RefundSummary refundNeeded,
        RefundSummary refunded,
        List<ItemSales> itemSales) {

    public record ByMethod(long BANK_TRANSFER, long CASH) {
    }

    public record RefundSummary(long count, long amount) {
    }

    /**
     * 항목별 판매 — 결제완료(PAID) 주문의 미취소 항목. itemType으로 메뉴(MENU)·자릿세(SEAT_FEE)·기타 항목(EXTRA)을 가른다.
     * 기타 항목은 쿠폰·할인이면 amount가 음수일 수 있다.
     */
    public record ItemSales(OrderItemType itemType, String name, long qty, long amount) {
    }
}
