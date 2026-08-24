package com.boothlock.boothlock_server.order.dto;

import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;

import java.time.OffsetDateTime;
import java.util.List;

/** C4 내 주문 조회 응답 — 명세서 C4의 {"orders":[...]} 형태 그대로 */
public record OrderListResponse(List<OrderSummary> orders) {

    /** 주문 1건 — canCancel은 저장값이 아니라 서버 계산 파생값 (명세서 C4) */
    public record OrderSummary(
            Long orderId,
            String orderNo,
            OrderStatus status,
            PaymentStatus paymentStatus,
            int totalAmount,
            List<OrderItemSummary> items,
            PaymentInfo payment,
            boolean canCancel,
            OffsetDateTime createdAt) {
    }

    public record OrderItemSummary(Long menuId, String menuName, int unitPrice, int qty) {
    }

    /** 입금 안내 — bankAccount는 부스 설정값만, depositorNameRule은 응답 시점 조립 (명세서 C3·C4) */
    public record PaymentInfo(String bankAccount, String depositorNameRule) {
    }
}
