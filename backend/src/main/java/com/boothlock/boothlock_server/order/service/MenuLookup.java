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

    /**
     * 주문 시점의 메뉴 상태 — price는 스냅샷 원본, visible=false(숨김)와 soldOut은 주문 불가.
     * staffOnly: "기타" 항목(category=ETC, 명세서 밖) — 운영자가 결제 모달에서만 넣는 추가 자릿세·쿠폰 등. 손님 주문(C3)에는
     * 없는 메뉴와 같게 취급하고, 음수 가격(할인)일 수 있다
     */
    record MenuInfo(Long menuId, String name, int price, boolean soldOut, boolean visible, boolean staffOnly) {

        /** 일반 메뉴 — 기타 항목이 생기기 전 호출부(테스트 픽스처 등)용 */
        public MenuInfo(Long menuId, String name, int price, boolean soldOut, boolean visible) {
            this(menuId, name, price, soldOut, visible, false);
        }

        public boolean orderable() {
            return visible && !soldOut;
        }
    }
}
