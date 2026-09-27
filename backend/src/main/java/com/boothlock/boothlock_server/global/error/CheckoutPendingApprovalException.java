package com.boothlock.boothlock_server.global.error;

/**
 * 409 CHECKOUT_PENDING_APPROVAL — O6 퇴실을 requireSettled=true("결제 완료" 버튼)로 불렀는데 종료할 세션에 승인대기(O28) 주문이 남아 있다.
 * 승인대기는 미결제 정의(UnpaidOrderRule)에서 빠져 CHECKOUT_UNPAID_REMAINS로는 안 잡히는데, 손님 결제 안내(C 결제 정보 화면)는
 * 승인대기 금액까지 더한 총액을 이체하라고 보여준다. 그대로 퇴실하면 그 주문은 조용히 자동 거절되고 손님이 보낸 돈과 확정 금액이 어긋난다.
 * 그래서 퇴실 전체를 롤백하고 승인·거절을 먼저 하라고 알린다. details.pendingOrderCount에 남은 건수를 싣는다
 */
public class CheckoutPendingApprovalException extends RuntimeException {
    private final long pendingOrderCount;

    public CheckoutPendingApprovalException(long pendingOrderCount) {
        super("승인대기 주문 " + pendingOrderCount + "건이 남아 있습니다. 먼저 승인하거나 거절한 뒤 다시 시도해주세요.");
        this.pendingOrderCount = pendingOrderCount;
    }

    public long getPendingOrderCount() { return pendingOrderCount; }
}
