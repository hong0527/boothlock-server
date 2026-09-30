package com.boothlock.boothlock_server.dashboard.dto;

import java.util.List;

/** 주문현황 메뉴별 "나감" 체크 목록 (명세서 밖) — 체크가 하나도 없는 주문은 빠진다 */
public record ServedItemsResponse(List<OrderServed> orders) {

    public record OrderServed(Long orderId, List<Long> itemIds) {
    }
}
