package com.boothlock.boothlock_server.order.domain;

/**
 * 주문 항목 종류 — MENU(실제 메뉴)와 SEAT_FEE(자릿세, 서버가 첫 주문에만 자동 부과하는 비메뉴 항목)를 가른다.
 * SEAT_FEE는 스태프가 O23/O23b로 수정·취소할 수 없다 (OrderEntity.requireEditableItem 참고).
 */
public enum OrderItemType {
    MENU, SEAT_FEE
}
