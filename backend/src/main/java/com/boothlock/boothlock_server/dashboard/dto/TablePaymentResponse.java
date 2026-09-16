package com.boothlock.boothlock_server.dashboard.dto;

import java.util.List;

/** O24 테이블 일괄 입금 확인 응답 — 이번에 확인 처리된 주문들(O10 OrderSummary 형태)과 그 합계 */
public record TablePaymentResponse(List<DashboardResponse.OrderSummary> orders, int totalAmount) {
}
