package com.boothlock.boothlock_server.global.domain;

/**
 * 주문 상태 축 (API 명세서 §2, v0.6.10 O28 신설). 전이: PENDING_APPROVAL → RECEIVED → DONE / CANCELED
 * (거절은 PENDING_APPROVAL → CANCELED, O13 재사용). 수기 주문(O14)은 PENDING_APPROVAL을 거치지 않고 RECEIVED로 시작한다
 * — 운영자가 직접 입력한 주문은 이미 스스로 승인한 것과 같다 (OrderEntity 생성자 참조)
 */
public enum OrderStatus { PENDING_APPROVAL, RECEIVED, DONE, CANCELED }
