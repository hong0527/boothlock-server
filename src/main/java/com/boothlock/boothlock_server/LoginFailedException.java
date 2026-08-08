package com.boothlock.boothlock_server;

/** 401 LOGIN_FAILED — 아이디/비밀번호 불일치. 남은 시도 횟수는 노출하지 않는다 (명세서 §1.4) */
public class LoginFailedException extends RuntimeException {
    public LoginFailedException() { super("아이디 또는 비밀번호가 올바르지 않습니다."); }
}
