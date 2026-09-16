package com.boothlock.boothlock_server.dashboard.dto;

import com.boothlock.boothlock_server.order.domain.PaymentMethod;

/**
 * O24 테이블 일괄 입금 확인 요청.
 * expectedTotal은 운영자 화면에 보였던 합계 — 서버 재계산 합계와 다르면 409 (그 사이 들어온 주문을 모르고 확인하지 않게).
 * method는 O11과 같은 결제 수단 — 매출 집계(O18)가 수단별로 나뉘어 일괄 처리에도 필요하다.
 */
public record TablePaymentRequest(Long tableId, Integer expectedTotal, PaymentMethod method) {
}
