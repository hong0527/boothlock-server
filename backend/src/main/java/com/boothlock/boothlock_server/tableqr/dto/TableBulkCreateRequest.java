package com.boothlock.boothlock_server.tableqr.dto;

import java.util.List;

/** O2 테이블 일괄 등록 요청 — count+labelPrefix 또는 labels 중 하나만 지정 (명세서 O2) */
public record TableBulkCreateRequest(Integer count, String labelPrefix, List<String> labels) {
}
