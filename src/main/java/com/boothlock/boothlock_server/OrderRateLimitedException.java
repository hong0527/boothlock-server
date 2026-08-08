package com.boothlock.boothlock_server;

/** 429 ORDER_RATE_LIMITED — 미결제 주문 상한(8건) 초과. 문구는 명세서 §1.4 확정 문장 */
public class OrderRateLimitedException extends RuntimeException {
    public OrderRateLimitedException() { super("미결제 주문이 많습니다. 입금 확인 후 추가 주문해주세요."); }
}
