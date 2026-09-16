package com.boothlock.boothlock_server.dashboard.dto;

/** O15 호출 확인 응답 — 이미 확인된 호출을 다시 확인해도 같은 응답 (명세서 O15) */
public record CallAckResponse(Long callId, boolean acked) {
}
