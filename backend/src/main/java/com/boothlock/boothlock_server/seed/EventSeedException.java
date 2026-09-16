package com.boothlock.boothlock_server.seed;

/** 시딩 입력·적용 실패 — 기동을 멈추게 하는 것이 목적이라 잡아서 삼키지 않는다 */
public class EventSeedException extends IllegalStateException {

    public EventSeedException(String message) {
        super(message);
    }

    public EventSeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
