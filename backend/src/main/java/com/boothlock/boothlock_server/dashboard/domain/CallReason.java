package com.boothlock.boothlock_server.dashboard.domain;

/**
 * 직원 호출 사유 (DB스키마 staff_call.reason, 명세서 C6).
 * PAYMENT(결제확인 호출, v0.6.11 신설) — 손님이 계좌이체 후 입금을 알리는 전용 호출. 일반 호출(HELP·WATER·ETC)과
 * 쿨다운을 공유하지 않는다(CallService.COOLDOWN 참고) — 입금을 막 알렸는데 방금 다른 이유로 호출했다고 막히면 안 된다
 */
public enum CallReason { HELP, WATER, ETC, PAYMENT }
