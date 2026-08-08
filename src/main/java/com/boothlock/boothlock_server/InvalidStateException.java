package com.boothlock.boothlock_server;

/** 409 INVALID_STATE — 현재 상태에서 허용되지 않는 전이 (명세서 §2 상태 머신) */
public class InvalidStateException extends RuntimeException {
    public InvalidStateException(String message) {
        super(message);
    }
}
