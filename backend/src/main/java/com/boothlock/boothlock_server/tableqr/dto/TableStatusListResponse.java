package com.boothlock.boothlock_server.tableqr.dto;

import java.util.List;

/** O3 좌석 현황 응답 (명세서 O3) */
public record TableStatusListResponse(List<TableStatusResponse> tables) {
}
