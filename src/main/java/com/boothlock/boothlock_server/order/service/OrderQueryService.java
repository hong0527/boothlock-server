package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.UnauthorizedException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.dto.OrderListResponse;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;

/**
 * C4 내 주문 조회 — 현재 세션의 주문 전체를 최신순으로, canCancel은 서버가 계산.
 * TODO(6강): 폴링의 세션 활동 인정(last_activity_at 갱신)은 세션 인증 연결 시 처리 (명세서 C4)
 */
@Service
public class OrderQueryService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final OrderRepository orderRepository;
    private final BoothRepository boothRepository;

    public OrderQueryService(OrderRepository orderRepository, BoothRepository boothRepository) {
        this.orderRepository = orderRepository;
        this.boothRepository = boothRepository;
    }

    @Transactional(readOnly = true)
    public OrderListResponse getOrders(Long sessionId) {
        // null이면 파생 쿼리가 IS NULL로 바뀌어 수기 주문이 통째로 노출된다 — 반드시 차단
        if (sessionId == null) {
            throw new UnauthorizedException("세션 정보가 없습니다");
        }
        List<OrderEntity> orders = orderRepository.findBySessionIdOrderByCreatedAtDescIdDesc(sessionId);
        if (orders.isEmpty()) {
            return new OrderListResponse(List.of());
        }
        String bankAccount = boothRepository.findById(orders.get(0).getBoothId())
                .map(BoothEntity::getBankAccount)
                .orElseThrow(() -> new IllegalStateException(
                        "주문의 부스를 찾을 수 없습니다 boothId=" + orders.get(0).getBoothId()));
        List<OrderListResponse.OrderSummary> summaries = orders.stream()
                .map(order -> toSummary(order, bankAccount))
                .toList();
        return new OrderListResponse(summaries);
    }

    private OrderListResponse.OrderSummary toSummary(OrderEntity order, String bankAccount) {
        List<OrderListResponse.OrderItemSummary> items = order.getItems().stream()
                .map(this::toItemSummary)
                .toList();
        return new OrderListResponse.OrderSummary(
                order.getId(),
                order.getOrderNo(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getTotalAmount(),
                items,
                new OrderListResponse.PaymentInfo(bankAccount, depositorNameRule(order.getOrderNo())),
                canCancel(order),
                order.getCreatedAt().atOffset(KST));
    }

    private OrderListResponse.OrderItemSummary toItemSummary(OrderItemEntity item) {
        return new OrderListResponse.OrderItemSummary(
                item.getMenuId(), item.getMenuName(), item.getUnitPrice(), item.getQty());
    }

    /** canCancel = RECEIVED && UNPAID — 저장하지 않고 매 응답마다 계산 (명세서 C4) */
    private boolean canCancel(OrderEntity order) {
        return order.getStatus() == OrderStatus.RECEIVED
                && order.getPaymentStatus() == PaymentStatus.UNPAID;
    }

    private String depositorNameRule(String orderNo) {
        return "입금자명을 '이름+" + orderNo + "'로 입력해주세요 (예: 김철수" + orderNo + ")";
    }
}
