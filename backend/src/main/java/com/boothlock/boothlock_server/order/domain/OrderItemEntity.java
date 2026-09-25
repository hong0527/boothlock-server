package com.boothlock.boothlock_server.order.domain;

import com.boothlock.boothlock_server.global.error.InvalidRequestException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 주문 항목 (DB스키마 §1 order_item). menu_name·unit_price는 주문 순간 스냅샷 — 이후 메뉴 변경과 무관하게 불변 */
@Entity
@Table(name = "order_item")
public class OrderItemEntity {

    // OrderCreateService.MAX_QTY(주문 생성 시 검증)와 같은 값 — 생성 후 수량 변경(O6)도 같은 상한을 지켜야 함
    private static final int MAX_QTY = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 참조용 — FK 강제하지 않음, 본체는 스냅샷 컬럼 (DB스키마 §1). 자릿세(SEAT_FEE) 항목은 실제 메뉴가 없어 NULL
    @Column(name = "menu_id")
    private Long menuId;

    @Column(name = "menu_name", nullable = false, length = 50)
    private String menuName;

    @Column(name = "unit_price", nullable = false)
    private int unitPrice;

    @Column(nullable = false)
    private int qty;

    // O6 결제 모달 항목 단위 취소 — 지운 게 아니라 숨김 처리 (감사·정산 기록 보존)
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean canceled = false;

    // MENU(기본) / SEAT_FEE(자릿세, 서버가 자동 부과) — SEAT_FEE는 스태프가 O23/O23b로 못 건드린다 (OrderEntity.requireEditableItem)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "item_type", nullable = false, length = 20, columnDefinition = "varchar(20) default 'MENU'")
    private OrderItemType itemType = OrderItemType.MENU;

    protected OrderItemEntity() {
    }

    public OrderItemEntity(Long menuId, String menuName, int unitPrice, int qty) {
        this.menuId = menuId;
        this.menuName = menuName;
        this.unitPrice = unitPrice;
        this.qty = qty;
    }

    /** 자릿세 — 첫 주문에만 서버가 자동으로 붙이는 비메뉴 항목. menuId 없음(실제 메뉴가 아니라서), qty는 인원수 */
    public static OrderItemEntity seatFee(int unitPrice, int partySize) {
        OrderItemEntity item = new OrderItemEntity(null, "자릿세", unitPrice, partySize);
        item.itemType = OrderItemType.SEAT_FEE;
        return item;
    }

    /** subtotal은 파생값 — 저장하지 않고 계산한다 (DB스키마 §3-3) */
    public int subtotal() {
        return unitPrice * qty;
    }

    public Long getId() {
        return id;
    }

    public Long getMenuId() {
        return menuId;
    }

    public String getMenuName() {
        return menuName;
    }

    public int getUnitPrice() {
        return unitPrice;
    }

    public int getQty() {
        return qty;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public OrderItemType getItemType() {
        return itemType;
    }

    /** O6 결제 모달 수량 +/- — 0 이하로는 못 내리고(제거는 cancel()로 별도 처리), 주문 생성 때와 같은 상한(30)을 넘길 수 없다 */
    public void updateQty(int qty) {
        requireValidQty(qty);
        this.qty = qty;
    }

    /** 수량 범위 검사(1~30) — 호출자가 품절 검사보다 먼저 400을 돌려주고 싶을 때 따로 부른다 */
    public static void requireValidQty(int qty) {
        if (qty < 1 || qty > MAX_QTY) {
            throw new InvalidRequestException("수량은 1~" + MAX_QTY + "개까지 가능합니다");
        }
    }

    /** O6 결제 모달 개별 "취소" — 지우지 않고 숨김 처리, 합계 재계산은 OrderEntity 몫 */
    public void cancel() {
        this.canceled = true;
    }
}
