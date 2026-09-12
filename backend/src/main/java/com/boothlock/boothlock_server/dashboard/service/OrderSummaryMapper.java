package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** OrderEntity → 대시보드 응답 변환 — O10 조회·O11/O12 처리 결과가 같은 형태를 쓴다 (명세서 O10·O11·O12) */
@Component
public class OrderSummaryMapper {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    public DashboardResponse.OrderSummary toOrderSummary(OrderEntity o) {
        return new DashboardResponse.OrderSummary(
                o.getId(), o.getOrderNo(), o.getStatus(), o.getPaymentStatus(), o.getPaymentMethod(),
                o.isManual(), o.getTotalAmount(), o.getItems().stream().map(this::toItemSummary).toList(),
                o.getCanceledBy(), atKst(o.getCanceledAt()), o.getCancelReason(),
                o.getApprovedBy(), atKst(o.getApprovedAt()),
                o.getRefundedBy(), atKst(o.getRefundedAt()),
                atKst(o.getCreatedAt()));
    }

    private DashboardResponse.OrderItemSummary toItemSummary(OrderItemEntity item) {
        return new DashboardResponse.OrderItemSummary(
                item.getMenuId(), item.getMenuName(), item.getUnitPrice(), item.getQty());
    }

    private OffsetDateTime atKst(LocalDateTime dt) {
        return dt == null ? null : dt.atOffset(KST);
    }
}
