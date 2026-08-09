package com.boothlock.boothlock_server.global.error;

/** 429 LOGIN_LOCKED — 로그인 연속 실패 잠금. details.retryAfterSeconds 포함 (명세서 §1.4) */
public class LoginLockedException extends RuntimeException {
    private final long retryAfterSeconds;

    public LoginLockedException(long retryAfterSeconds) {
        super("로그인이 잠시 잠겼습니다. 잠시 후 다시 시도해주세요.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
