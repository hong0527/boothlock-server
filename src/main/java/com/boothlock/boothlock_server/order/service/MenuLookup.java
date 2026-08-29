package com.boothlock.boothlock_server.order.service;

import java.util.Collection;
import java.util.List;

/**
 * 주문이 메뉴 파트에 묻는 창구 — 주문 파트가 메뉴 엔티티에 직접 의존하지 않게 한다.
 * 실제 구현은 메뉴 파트(권희원) 도메인 머지 후 연결한다.
 */
public interface MenuLookup {

    /** 해당 부스의 메뉴만 조회 — 타 부스·미존재 menuId는 결과에서 빠진다 (명세서 C3 5단계) */
    List<MenuInfo> findByBoothIdAndMenuIds(Long boothId, Collection<Long> menuIds);

    /** 주문 시점의 메뉴 상태 — price는 스냅샷 원본, visible=false(숨김)와 soldOut은 주문 불가 */
    record MenuInfo(Long menuId, String name, int price, boolean soldOut, boolean visible) {

        public boolean orderable() {
            return visible && !soldOut;
        }
    }
}
