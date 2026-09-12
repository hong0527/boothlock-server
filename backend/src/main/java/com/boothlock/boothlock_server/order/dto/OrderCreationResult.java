package com.boothlock.boothlock_server.order.dto;

/**
 * C3 처리 결과 — 새로 만들었으면 201, 멱등키 재요청이면 기존 주문을 200으로 반환한다 (명세서 C3).
 * 상태 코드 결정은 컨트롤러 몫이라 서비스는 created 플래그만 알려준다.
 */
public record OrderCreationResult(OrderCreateResponse response, boolean created) {
}
