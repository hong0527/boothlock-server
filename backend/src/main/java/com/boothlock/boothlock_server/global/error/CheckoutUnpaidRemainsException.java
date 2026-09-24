package com.boothlock.boothlock_server.global.error;

/**
 * 409 CHECKOUT_UNPAID_REMAINS — O6 퇴실을 requireSettled=true("결제 완료" 버튼)로 불렀는데 종료할 세션에 미결제가 남아 있다.
 * O24 일괄 입금 확인과 O6 사이에 들어온 새 주문이 퇴실의 자동 완료(DONE)에 묻혀 주방 대기열에서 사라지지 않게,
 * 퇴실 전체를 롤백하고 운영자에게 다시 확인하라고 알린다. details.unpaidOrderCount에 남은 건수를 싣는다
 */
public class CheckoutUnpaidRemainsException extends RuntimeException {
    private final long unpaidOrderCount;

    public CheckoutUnpaidRemainsException(long unpaidOrderCount) {
        super("결제 확인 뒤 새 미결제 주문 " + unpaidOrderCount + "건이 들어왔습니다. 주문을 확인한 뒤 다시 시도해주세요.");
        this.unpaidOrderCount = unpaidOrderCount;
    }

    public long getUnpaidOrderCount() { return unpaidOrderCount; }
}
