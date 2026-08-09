package com.boothlock.boothlock_server.global.error;

/** 409 ALREADY_PAID — 이미 결제 완료된 주문에 입금확인 재시도 (명세서 §1.4) */
public class AlreadyPaidException extends RuntimeException {
    public AlreadyPaidException() { super("이미 결제 완료된 주문입니다."); }
}
