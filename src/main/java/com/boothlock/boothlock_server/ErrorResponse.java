package com.boothlock.boothlock_server;

/**
 * 공통 에러 응답 (API 명세서 §1.3) — 전 파트 공용.
 * {"error": {"code": "...", "message": "...", "details": ...}}
 * details는 에러별로 형태가 다르다 — SOLD_OUT은 메뉴 목록(배열),
 * CALL_COOLDOWN·LOGIN_LOCKED는 {"retryAfterSeconds": n}(객체), 그 외는 null.
 */
public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message, Object details) {}

    /** SOLD_OUT details 배열의 원소 */
    public record ErrorDetail(Long menuId, String menuName) {}
}
