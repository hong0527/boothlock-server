package com.boothlock.boothlock_server.dashboard.dto;

import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;

import java.time.OffsetDateTime;
import java.util.List;

/** O10 실시간 대시보드 응답 — 주문 목록 + 미확인 호출을 한 번에 (명세서 O10) */
public record DashboardResponse(List<OrderSummary> orders, List<CallSummary> calls) {

    public record OrderSummary(
            Long orderId,
            String orderNo,
            String tableLabel,
            OrderStatus status,
            PaymentStatus paymentStatus,
            PaymentMethod paymentMethod,
            boolean manual,
            int totalAmount,
            List<OrderItemSummary> items,
            String canceledBy,
            OffsetDateTime canceledAt,
            String cancelReason,
            String approvedBy,
            OffsetDateTime approvedAt,
            String refundedBy,
            OffsetDateTime refundedAt,
            OffsetDateTime createdAt,
            /** 주문이 붙은 테이블 세션 PK — O3 session.id와 맞춰 "지금 앉은 손님" 주문을 고른다. 테이블 미지정 수기 주문은 null. 기존 필드 뒤에 붙였다 */
            Long sessionId) {
    }

    public record OrderItemSummary(Long itemId, Long menuId, String menuName, int unitPrice, int qty) {
    }

    public record CallSummary(Long callId, String tableLabel, String reason, OffsetDateTime createdAt) {
    }
}
