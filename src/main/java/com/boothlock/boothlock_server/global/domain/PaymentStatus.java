package com.boothlock.boothlock_server.global.domain;

/** 결제 상태 축 (API 명세서 §2). 주문 상태와 독립. UNPAID → PAID → REFUND_NEEDED → REFUNDED */
public enum PaymentStatus { UNPAID, PAID, REFUND_NEEDED, REFUNDED }
