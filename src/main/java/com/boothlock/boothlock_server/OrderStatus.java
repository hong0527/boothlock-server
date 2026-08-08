package com.boothlock.boothlock_server;

/** 주문 상태 축 (API 명세서 §2). 전이: RECEIVED → DONE / CANCELED */
public enum OrderStatus { RECEIVED, DONE, CANCELED }
