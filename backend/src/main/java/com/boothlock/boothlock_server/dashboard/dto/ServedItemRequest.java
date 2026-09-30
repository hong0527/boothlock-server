package com.boothlock.boothlock_server.dashboard.dto;

import jakarta.validation.constraints.NotNull;

/** 주문현황 메뉴별 "나감" 체크/해제 요청 */
public record ServedItemRequest(@NotNull Boolean served) {
}
