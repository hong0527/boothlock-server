package com.boothlock.boothlock_server.global.error;

/** 404 NOT_FOUND — 존재하지 않는 리소스 (타 부스·타 세션 접근도 404로 은닉 — 명세서 §1.4) */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
