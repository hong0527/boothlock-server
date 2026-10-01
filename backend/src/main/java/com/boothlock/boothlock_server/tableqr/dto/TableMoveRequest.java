package com.boothlock.boothlock_server.tableqr.dto;

/** 자리 이동(명세서 밖) 요청 — 손님을 옮길 빈 테이블 id */
public record TableMoveRequest(Long toTableId) {
}
