package com.boothlock.boothlock_server.order.domain;

import com.boothlock.boothlock_server.global.error.InvalidRequestException;

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

    // OrderCreateService.MAX_QTY(주문 생성 시 검증)와 같은 값 — 생성 후 수량 변경(O6)도 같은 상한을 지켜야 함
    private static final int MAX_QTY = 30;

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

    // O6 결제 모달 항목 단위 취소 — 지운 게 아니라 숨김 처리 (감사·정산 기록 보존)
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean canceled = false;

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

    public boolean isCanceled() {
        return canceled;
    }

    /** O6 결제 모달 수량 +/- — 0 이하로는 못 내리고(제거는 cancel()로 별도 처리), 주문 생성 때와 같은 상한(30)을 넘길 수 없다 */
    public void updateQty(int qty) {
        if (qty < 1 || qty > MAX_QTY) {
            throw new InvalidRequestException("수량은 1~" + MAX_QTY + "개까지 가능합니다");
        }
        this.qty = qty;
    }

    /** O6 결제 모달 개별 "취소" — 지우지 않고 숨김 처리, 합계 재계산은 OrderEntity 몫 */
    public void cancel() {
        this.canceled = true;
    }
}
