package com.boothlock.boothlock_server.tableqr.dto;

/**
 * C1 세션 발급 응답 — 활성 세션이 있으면 복원(restored:true) (명세서 C1).
 * partySize(자릿세 파일럿): 세션에 저장된 인원수, 없으면 null — 프론트는 restored가 아니라 이 값으로 인원 선택 화면을 띄울지 정한다
 * (두 번째 폰·재스캔은 restored지만 아직 아무도 인원을 고르지 않았을 수 있다)
 */
public record TableSessionResponse(
        String sessionToken,
        Booth booth,
        Table table,
        boolean restored,
        Integer partySize) {

    public record Booth(String name, boolean isOpen) {
    }

    public record Table(String label) {
    }
}
