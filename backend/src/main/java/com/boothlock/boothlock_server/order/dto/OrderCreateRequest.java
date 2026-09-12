package com.boothlock.boothlock_server.order.dto;

import java.util.List;

/** C3 주문 생성 요청 — 금액 필드가 없는 것이 설계다. 가격은 서버가 DB에서 재계산한다 (명세서 C3) */
public record OrderCreateRequest(List<OrderItemRequest> items) {

    public record OrderItemRequest(Long menuId, Integer qty) {
    }
}
