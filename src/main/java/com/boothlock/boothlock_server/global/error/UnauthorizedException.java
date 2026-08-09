package com.boothlock.boothlock_server.global.error;

/** 401 UNAUTHORIZED — 토큰 없음·서명 오류·만료·무효화 (명세서 §1.4) */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException() { super("인증이 필요합니다."); }
    public UnauthorizedException(String message) { super(message); }
}
