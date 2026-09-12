package com.boothlock.boothlock_server.order.dto;

import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * C3 주문 생성 응답 — C4와 형태가 다르다 (명세서 C3): items에 subtotal, payment에 method가 있고 canCancel이 없다.
 * 그래서 C4·C5용 OrderListResponse.OrderSummary를 재사용하지 않는다.
 */
public record OrderCreateResponse(
        Long orderId,
        String orderNo,
        OrderStatus status,
        PaymentStatus paymentStatus,
        int totalAmount,
        List<OrderItemResponse> items,
        PaymentGuide payment,
        OffsetDateTime createdAt) {

    /** subtotal은 단가×수량 파생값 — 저장하지 않고 응답 시점에 계산한다 (명세서 C3) */
    public record OrderItemResponse(Long menuId, String menuName, int unitPrice, int qty, int subtotal) {
    }

    public record PaymentGuide(PaymentMethod method, String bankAccount, String depositorNameRule) {
    }
}
