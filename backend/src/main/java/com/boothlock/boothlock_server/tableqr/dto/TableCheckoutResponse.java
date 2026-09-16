package com.boothlock.boothlock_server.tableqr.dto;

/** O6 퇴실·초기화 응답 — 막지 않고 미결제 여부만 경고로 알려준다(명세서 O6 "Should") */
public record TableCheckoutResponse(boolean unpaidWarning) {
}
