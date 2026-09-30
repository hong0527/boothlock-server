package com.boothlock.boothlock_server.tableqr.dto;

/** O25 자리 이동 요청(명세서 밖 파일럿) — 대상 테이블은 비어 있어야 한다(409). */
public record TableMoveRequest(Long targetTableId) {
}
