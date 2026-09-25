package com.boothlock.boothlock_server.dashboard.dto;

import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderItemType;
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

    /** itemType=SEAT_FEE(자릿세, 명세서 밖)면 menuId는 null — 실제 메뉴가 아니라 스태프 화면(결제창)의 수정·취소 버튼을 막는 근거로 쓴다 */
    public record OrderItemSummary(Long itemId, Long menuId, String menuName, int unitPrice, int qty, OrderItemType itemType) {
    }

    public record CallSummary(Long callId, String tableLabel, String reason, OffsetDateTime createdAt) {
    }
}
