package com.boothlock.boothlock_server.tableqr.dto;

/** 자리 이동 요청 — 도착 테이블과 화면에서 확인한 출발 세션 */
public record TableMoveRequest(Long toTableId, Long sessionId) {
}
