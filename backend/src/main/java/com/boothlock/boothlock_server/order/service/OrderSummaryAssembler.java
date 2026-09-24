package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
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

    private OrderListResponse.OrderSummary toSummary(OrderEntity order, String bankAccount, String depositorName) {
        // 결제 모달에서 개별 취소된 항목은 손님 화면(C4·C5 응답)에서도 뺀다 — 안 빼면 totalAmount와 항목 합이 어긋난다 (대시보드 매퍼와 같은 규칙)
        List<OrderListResponse.OrderItemSummary> items = order.getItems().stream()
                .filter(item -> !item.isCanceled())
                .map(this::toItemSummary)
                .toList();
        return new OrderListResponse.OrderSummary(
                order.getId(),
                order.getOrderNo(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getTotalAmount(),
                items,
                new OrderListResponse.PaymentInfo(bankAccount, depositorName, depositorNameRule(order.getOrderNo())),
                order.canCancel(),   // 판정은 엔티티가 — C5 실행 조건과 어긋나지 않게 한 곳에서 계산
                order.getCreatedAt().atOffset(KST));
    }

    private OrderListResponse.OrderItemSummary toItemSummary(OrderItemEntity item) {
        return new OrderListResponse.OrderItemSummary(
                item.getMenuId(), item.getMenuName(), item.getUnitPrice(), item.getQty(), item.getItemType());
    }

    private String depositorNameRule(String orderNo) {
        return "입금자명을 '이름+" + orderNo + "'로 입력해주세요 (예: 김철수" + orderNo + ")";
    }

    public OrderListResponse.OrderSummary assemble(OrderEntity order)
    {
        BoothEntity booth = boothOf(order.getBoothId());
        return toSummary(order, booth.getBankAccount(), booth.getDepositorName());
    }

    public List<OrderListResponse.OrderSummary> assembleAll(List<OrderEntity> orders)
    {
        if (orders.isEmpty()) {
            return List.of();
        }

        BoothEntity booth = boothOf(orders.get(0).getBoothId());

        return orders.stream()
                .map(order -> toSummary(order, booth.getBankAccount(), booth.getDepositorName()))
                .toList();

    }

    private BoothEntity boothOf(Long boothId) {
        return boothRepository.findById(boothId)
                .orElseThrow(() -> new IllegalStateException("주문의 부스를 찾을 수 없습니다 boothId=" + boothId));
    }
}
