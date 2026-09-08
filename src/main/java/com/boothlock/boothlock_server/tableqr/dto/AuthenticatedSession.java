package com.boothlock.boothlock_server.tableqr.dto;

/** 세션 인증 계층의 결과물 — X-Session-Token 헤더로 인증된 요청이 주문 관련 API에 넘기는 값 (명세서 C3·C4·C5 공통) */
public record AuthenticatedSession(Long sessionId, Long boothId, String tableLabel) {
}
