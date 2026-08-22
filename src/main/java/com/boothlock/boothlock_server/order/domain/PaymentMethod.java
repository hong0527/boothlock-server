package com.boothlock.boothlock_server.order.domain;

/** 결제 수단 (DB스키마 §1 orders.payment_method). 입금확인(O11) 시 기록 */
public enum PaymentMethod { BANK_TRANSFER, CASH }
