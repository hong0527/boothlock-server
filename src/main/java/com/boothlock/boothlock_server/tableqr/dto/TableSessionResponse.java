package com.boothlock.boothlock_server.tableqr.dto;

/** C1 세션 발급 응답 — 활성 세션이 있으면 복원(restored:true) (명세서 C1) */
public record TableSessionResponse(
        String sessionToken,
        Booth booth,
        Table table,
        boolean restored) {

    public record Booth(String name, boolean isOpen) {
    }

    public record Table(String label) {
    }
}
