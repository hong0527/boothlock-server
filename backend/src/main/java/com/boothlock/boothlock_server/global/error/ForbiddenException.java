package com.boothlock.boothlock_server.global.error;

/** 403 FORBIDDEN — 롤 부족만 (STAFF의 계좌 변경 등). 타 부스 리소스 접근은 404로 (명세서 §1.4) */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException() { super("권한이 없습니다."); }
    public ForbiddenException(String message) { super(message); }
}
