package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.dashboard.dto.ManualOrderRequest;
import com.boothlock.boothlock_server.global.error.ErrorResponse;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.error.OrderClosedException;
import com.boothlock.boothlock_server.global.error.SoldOutException;
import com.boothlock.boothlock_server.order.dto.OrderCreateRequest;
import com.boothlock.boothlock_server.order.service.MenuLookup;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * O14 테이블 지정 수기 주문의 사전 검증 — 세션을 만들기 전에 주문이 거절될지 먼저 판정한다.
 * 세션 자동 생성과 테이블 OCCUPIED 전환은 되돌리지 않는 부수효과라, 품절·마감·잘못된 요청으로 실패할 주문이
 * 빈 테이블을 사용중으로 바꾸면 안 된다 (감사 M4 — 거절된 수기 주문의 테이블 점유).
 *
 * 규칙·메시지는 C3(OrderCreateService)의 validateRequest·isOpen·resolveMenus와 같다. 그쪽은 private이고 주문 파일은
 * 주문 파트 소유라 여기서 같은 규칙을 한 번 더 건다 — 저장 단계(createManual)가 같은 검증을 다시 하므로 여기서 놓친 것이
 * 저장되는 일은 없다. 주문 파트가 세션 결정을 뒤로 미루는 진입점(Supplier 방식 createManual)을 열면 이 클래스는 지운다.
 */
@Component
public class ManualOrderPreflight {

    private static final int MAX_ITEM_KINDS = 20;
    private static final int MAX_QTY = 30;

    private final BoothRepository boothRepository;
    private final MenuLookup menuLookup;

    public ManualOrderPreflight(BoothRepository boothRepository, MenuLookup menuLookup) {
        this.boothRepository = boothRepository;
        this.menuLookup = menuLookup;
    }

    /** 요청 형식(400) → 부스 접수 스위치(409 ORDER_CLOSED) → 메뉴 미존재·타 부스(400)·숨김·품절(409 SOLD_OUT) 순서로 C3과 같다 */
    @Transactional(readOnly = true)
    public void check(Long boothId, ManualOrderRequest request) {
        validateItems(request);

        BoothEntity booth = boothRepository.findById(boothId)
                .orElseThrow(() -> new NotFoundException("부스를 찾을 수 없습니다"));
        if (!booth.isOpen()) {
            throw new OrderClosedException();
        }

        List<Long> menuIds = request.items().stream().map(OrderCreateRequest.OrderItemRequest::menuId).toList();
        Set<Long> found = new HashSet<>();
        List<ErrorResponse.ErrorDetail> unavailable = new java.util.ArrayList<>();
        for (MenuLookup.MenuInfo menu : menuLookup.findByBoothIdAndMenuIds(boothId, menuIds)) {
            found.add(menu.menuId());
            if (!menu.orderable()) {
                unavailable.add(new ErrorResponse.ErrorDetail(menu.menuId(), menu.name()));
            }
        }
        for (Long menuId : menuIds) {
            if (!found.contains(menuId)) {
                throw new InvalidRequestException("존재하지 않는 메뉴입니다 menuId=" + menuId);
            }
        }
        if (!unavailable.isEmpty()) {
            throw new SoldOutException(unavailable);
        }
    }

    private void validateItems(ManualOrderRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new InvalidRequestException("주문 항목이 필요합니다");
        }
        if (request.items().size() > MAX_ITEM_KINDS) {
            throw new InvalidRequestException("한 번에 주문할 수 있는 메뉴는 " + MAX_ITEM_KINDS + "종까지입니다");
        }
        for (OrderCreateRequest.OrderItemRequest item : request.items()) {
            if (item == null || item.menuId() == null || item.qty() == null) {
                throw new InvalidRequestException("메뉴와 수량을 모두 입력해주세요");
            }
            if (item.qty() < 1 || item.qty() > MAX_QTY) {
                throw new InvalidRequestException("수량은 1~" + MAX_QTY + "개까지 가능합니다");
            }
        }
        long distinctMenus = request.items().stream()
                .map(OrderCreateRequest.OrderItemRequest::menuId).distinct().count();
        if (distinctMenus != request.items().size()) {
            throw new InvalidRequestException("같은 메뉴가 중복되었습니다");
        }
    }
}
