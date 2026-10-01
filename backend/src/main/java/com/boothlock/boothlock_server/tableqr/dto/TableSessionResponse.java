package com.boothlock.boothlock_server.tableqr.dto;

/**
 * C1 세션 발급 응답 — 활성 세션이 있으면 복원(restored:true) (명세서 C1).
 * partySize(자릿세 파일럿): 세션에 저장된 인원수, 없으면 null — 프론트는 restored가 아니라 이 값으로 인원 선택 화면을 띄울지 정한다
 * (두 번째 폰·재스캔은 restored지만 아직 아무도 인원을 고르지 않았을 수 있다)
 * seatFeeCharged(자릿세 파일럿): 이 세션의 자릿세가 이미 처리됐다고 보는가 — 자기 세션 자릿세(면제 포함), 또는 유휴 인계로
 * 이어진 앞 세션의 같은 영업일 자릿세(OrderWriter.isSeatFeeHandled). 주문 확인 화면이 받지 않을 자릿세를 미리 보여주지 않게 한다.
 * booth.seatFeePerPerson(자릿세 파일럿): 부스가 정한 1인당 자릿세 — 인원 선택 화면 안내용. 0이면 자릿세 없음
 * booth.minOrderAmount(파일럿): 첫 주문 최소 금액(자릿세 제외) — 장바구니 안내용. 0이면 제한 없음
 */
public record TableSessionResponse(
        String sessionToken,
        Booth booth,
        Table table,
        boolean restored,
        Integer partySize,
        boolean seatFeeCharged) {

    public record Booth(String name, boolean isOpen, int seatFeePerPerson, int minOrderAmount) {
    }

    public record Table(String label) {
    }
}
