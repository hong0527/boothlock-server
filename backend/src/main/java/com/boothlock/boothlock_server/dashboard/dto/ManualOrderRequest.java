package com.boothlock.boothlock_server.dashboard.dto;

import com.boothlock.boothlock_server.order.dto.OrderCreateRequest;

import java.util.List;

/**
 * O14 수기 주문 요청 (명세서 O14).
 * tableId 생략 가능(테이블 미지정 — orderNo는 M-{통산}). 나머지 검증은 소비자 주문(C3)과 동일해서
 * items는 OrderCreateRequest의 아이템 타입을 그대로 재사용한다.
 */
public record ManualOrderRequest(Long tableId, List<OrderCreateRequest.OrderItemRequest> items) {
}
