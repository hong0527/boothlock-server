package com.boothlock.boothlock_server;

/** 409 ORDER_CLOSED — 부스 주문 접수 OFF 상태에서 주문 시도 (명세서 §1.4, 기능 8.3) */
public class OrderClosedException extends RuntimeException {
    public OrderClosedException() { super("지금은 주문을 받지 않습니다."); }
}
