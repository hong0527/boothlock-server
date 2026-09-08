package com.boothlock.boothlock_server.tableqr.dto;

import com.boothlock.boothlock_server.tableqr.domain.TableStatus;

/** O3 좌석 현황 응답의 테이블 1건 (명세서 O3) — needsCleanup은 OCCUPIED인데 활성 세션이 없는 경우다 */
public record TableStatusResponse(Long id, String label, TableStatus status, boolean needsCleanup) {
}
