package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.global.error.NotImplementedException;

import java.util.Collection;
import java.util.List;

/**
 * 메뉴 도메인 머지 전까지의 임시 구현 — 컨텍스트 기동용.
 * 빈 등록은 MenuLookupConfig가 조건부로 한다(@Component를 붙이면 메뉴 파트 구현과 빈이 둘이 되어 기동이 깨진다).
 * TODO(메뉴 파트 머지 후): 이 클래스와 MenuLookupConfig를 함께 삭제한다.
 */
public class PendingMenuLookup implements MenuLookup {

    @Override
    public List<MenuInfo> findByBoothIdAndMenuIds(Long boothId, Collection<Long> menuIds) {
        throw new NotImplementedException("메뉴 조회 (메뉴 도메인 머지 후 연결)");
    }
}
