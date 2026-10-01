package com.boothlock.boothlock_server.tableqr.dto;

/** 자리 이동(명세서 밖) 응답 — 옮긴 세션과 출발·도착 테이블 */
public record TableMoveResponse(Long sessionId, Long fromTableId, String fromLabel, Long toTableId, String toLabel) {
}
