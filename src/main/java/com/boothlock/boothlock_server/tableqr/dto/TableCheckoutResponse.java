package com.boothlock.boothlock_server.tableqr.dto;

import com.boothlock.boothlock_server.tableqr.domain.TableStatus;

/**
 * O6 퇴실·초기화 응답 (명세서 O6).
 * warning은 미결제 주문이 남아있던 채로 퇴실 처리됐을 때만 채워지고, 문제없으면 null이다.
 */
public record TableCheckoutResponse(Long id, String label, TableStatus status, String warning) {
}
