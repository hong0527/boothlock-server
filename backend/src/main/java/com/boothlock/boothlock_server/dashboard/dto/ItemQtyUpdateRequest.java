package com.boothlock.boothlock_server.dashboard.dto;

import jakarta.validation.constraints.Min;

/** O6 결제 모달 항목 수량 변경 요청 */
public record ItemQtyUpdateRequest(@Min(1) int qty) {
}
