package com.boothlock.boothlock_server.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 주문 항목 (DB스키마 §1 order_item). menu_name·unit_price는 주문 순간 스냅샷 — 이후 메뉴 변경과 무관하게 불변 */
@Entity
@Table(name = "order_item")
public class OrderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 참조용 — FK 강제하지 않음, 본체는 스냅샷 컬럼 (DB스키마 §1)
    @Column(name = "menu_id", nullable = false)
    private Long menuId;

    @Column(name = "menu_name", nullable = false, length = 50)
    private String menuName;

    @Column(name = "unit_price", nullable = false)
    private int unitPrice;

    @Column(nullable = false)
    private int qty;

    protected OrderItemEntity() {
    }

    public OrderItemEntity(Long menuId, String menuName, int unitPrice, int qty) {
        this.menuId = menuId;
        this.menuName = menuName;
        this.unitPrice = unitPrice;
        this.qty = qty;
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
}
