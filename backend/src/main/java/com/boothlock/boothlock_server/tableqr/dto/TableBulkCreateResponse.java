package com.boothlock.boothlock_server.tableqr.dto;

import java.util.List;

/** O2 테이블 일괄 등록 응답 (명세서 O2) */
public record TableBulkCreateResponse(List<TableAdminResponse> tables) {
}
