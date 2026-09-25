package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** OrderEntity → 대시보드 응답 변환 — O10 조회·O11/O12 처리 결과가 같은 형태를 쓴다 (명세서 O10·O11·O12) */
@Component
public class OrderSummaryMapper {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    public DashboardResponse.OrderSummary toOrderSummary(OrderEntity o) {
        // O6 결제 모달엔 취소된 항목을 안 보여준다 — DB엔 감사·정산용으로 남기고 응답에서만 제외
        List<DashboardResponse.OrderItemSummary> visibleItems = o.getItems().stream()
                .filter(item -> !item.isCanceled())
                .map(this::toItemSummary)
                .toList();
        return new DashboardResponse.OrderSummary(
                o.getId(), o.getOrderNo(), o.getTableLabel(), o.getStatus(), o.getPaymentStatus(), o.getPaymentMethod(),
                o.isManual(), o.getTotalAmount(), visibleItems,
                o.getCanceledBy(), atKst(o.getCanceledAt()), o.getCancelReason(),
                o.getApprovedBy(), atKst(o.getApprovedAt()),
                o.getRefundedBy(), atKst(o.getRefundedAt()),
                atKst(o.getCreatedAt()),
                o.getSessionId());
    }

    private DashboardResponse.OrderItemSummary toItemSummary(OrderItemEntity item) {
        return new DashboardResponse.OrderItemSummary(
                item.getId(), item.getMenuId(), item.getMenuName(), item.getUnitPrice(), item.getQty(), item.getItemType());
    }

    private OffsetDateTime atKst(LocalDateTime dt) {
        return dt == null ? null : dt.atOffset(KST);
    }
}
