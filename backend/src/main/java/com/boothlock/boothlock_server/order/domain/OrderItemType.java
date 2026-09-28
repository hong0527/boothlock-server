package com.boothlock.boothlock_server.order.domain;

/**
 * 주문 항목 종류 — MENU(실제 메뉴), SEAT_FEE(자릿세, 인원 선택 때 서버가 자동 부과하는 비메뉴 항목),
 * EXTRA(기타 항목 — 운영자가 결제 모달 기타 탭에서 넣는 추가 자릿세·쿠폰 등, 명세서 밖. 조리할 것이 없고 음수일 수 있다)를 가른다.
 * 셋 다 미결제면 스태프가 결제 모달(O23/O23b)로 수량·취소를 고칠 수 있다 — 자릿세는 인원 바로잡기·면제용.
 */
public enum OrderItemType {
    MENU, SEAT_FEE, EXTRA
}
