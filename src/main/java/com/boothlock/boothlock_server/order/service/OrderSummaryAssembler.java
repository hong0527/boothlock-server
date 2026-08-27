package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.dto.OrderListResponse;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.List;

@Component
public class OrderSummaryAssembler {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final BoothRepository boothRepository;

    public OrderSummaryAssembler(BoothRepository boothRepository) {
        this.boothRepository = boothRepository;
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

    private boolean canCancel(OrderEntity order) {
        return order.getStatus() == OrderStatus.RECEIVED
                && order.getPaymentStatus() == PaymentStatus.UNPAID;
    }

    private String depositorNameRule(String orderNo) {
        return "입금자명을 '이름+" + orderNo + "'로 입력해주세요 (예: 김철수" + orderNo + ")";
    }

    public OrderListResponse.OrderSummary assemble(OrderEntity order)
    {
        return toSummary(order, bankAccountOf(order.getBoothId()));
    }

    public List<OrderListResponse.OrderSummary> assembleAll(List<OrderEntity> orders)
    {
        if (orders.isEmpty()) {
            return List.of();
        }

        String bankAccount = bankAccountOf(orders.get(0).getBoothId());

        return orders.stream()
                .map(order -> toSummary(order,bankAccount))
                .toList();

    }

    private String bankAccountOf(Long boothId) {
        return boothRepository.findById(boothId)
                .map(BoothEntity::getBankAccount)
                .orElseThrow(() -> new IllegalStateException("주문의 부스를 찾을 수 없습니다 boothId=" + boothId));
    }
}
