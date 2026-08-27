package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.global.error.UnauthorizedException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.dto.OrderListResponse;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * C4 내 주문 조회 — 현재 세션의 주문 전체를 최신순으로, canCancel은 서버가 계산.
 * TODO(6강): 폴링의 세션 활동 인정(last_activity_at 갱신)은 세션 인증 연결 시 처리 (명세서 C4)
 */
@Service
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final OrderSummaryAssembler assembler;

    public OrderQueryService(OrderRepository orderRepository, OrderSummaryAssembler assembler) {
        this.orderRepository = orderRepository;
        this.assembler = assembler;
    }

    @Transactional(readOnly = true)
    public OrderListResponse getOrders(Long sessionId) {
        // null이면 파생 쿼리가 IS NULL로 바뀌어 수기 주문이 통째로 노출된다 — 반드시 차단
        if (sessionId == null) {
            throw new UnauthorizedException("세션 정보가 없습니다");
        }
        List<OrderEntity> orders = orderRepository.findBySessionIdOrderByCreatedAtDescIdDesc(sessionId);
        return new OrderListResponse(assembler.assembleAll(orders));
    }
}
