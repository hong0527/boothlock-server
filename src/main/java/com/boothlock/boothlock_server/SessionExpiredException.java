package com.boothlock.boothlock_server;

/** 410 SESSION_EXPIRED — 퇴실·만료 처리된 세션 토큰으로 호출 (명세서 §1.4) */
public class SessionExpiredException extends RuntimeException {
    public SessionExpiredException() { super("세션이 만료되었습니다. 테이블 QR을 다시 스캔해주세요."); }
}
