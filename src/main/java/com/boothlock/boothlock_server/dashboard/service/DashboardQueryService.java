package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** O10 실시간 대시보드 — 주문 목록 + 미확인 호출을 한 번에 조회 (명세서 O10) */
@Service
public class DashboardQueryService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final OrderRepository orderRepository;
    private final StaffCallRepository staffCallRepository;
    private final OrderSummaryMapper orderSummaryMapper;

    public DashboardQueryService(OrderRepository orderRepository, StaffCallRepository staffCallRepository,
            OrderSummaryMapper orderSummaryMapper) {
        this.orderRepository = orderRepository;
        this.staffCallRepository = staffCallRepository;
        this.orderSummaryMapper = orderSummaryMapper;
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(Long boothId, OrderStatus status, PaymentStatus paymentStatus,
                                           LocalDate businessDate, String q) {
        List<OrderEntity> orders = orderRepository.searchForDashboard(boothId, status, paymentStatus, businessDate, q);
        List<StaffCallEntity> calls = staffCallRepository.findUnackedByBoothId(boothId);

        return new DashboardResponse(
                orders.stream().map(orderSummaryMapper::toOrderSummary).toList(),
                calls.stream().map(this::toCallSummary).toList());
    }

    private DashboardResponse.CallSummary toCallSummary(StaffCallEntity c) {
        return new DashboardResponse.CallSummary(
                c.getId(), c.getSession().getTable().getLabel(), c.getReason().name(), atKst(c.getCreatedAt()));
    }

    private OffsetDateTime atKst(LocalDateTime dt) {
        return dt == null ? null : dt.atOffset(KST);
    }
}
