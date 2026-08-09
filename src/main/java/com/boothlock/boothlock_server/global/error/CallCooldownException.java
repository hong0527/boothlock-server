package com.boothlock.boothlock_server.global.error;

/** 429 CALL_COOLDOWN — 직원 호출 30초 내 재시도. details.retryAfterSeconds 포함 (명세서 §1.4) */
public class CallCooldownException extends RuntimeException {
    private final long retryAfterSeconds;

    public CallCooldownException(long retryAfterSeconds) {
        super("잠시 후 다시 호출해주세요.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
