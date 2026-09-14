package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.springframework.data.domain.Limit;
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
    // MVP: 탭(상태)별 화면엔 최신 30건만 — 그 이전 주문은 q(주문번호 검색)로 찾는다
    private static final Limit DASHBOARD_LIST_LIMIT = Limit.of(30);

    private final OrderRepository orderRepository;
    private final StaffCallRepository staffCallRepository;
    private final OrderSummaryMapper orderSummaryMapper;
    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;

    public DashboardQueryService(OrderRepository orderRepository, StaffCallRepository staffCallRepository,
            OrderSummaryMapper orderSummaryMapper, BoothJwtProvider jwtProvider, BoothInfoService boothInfoService) {
        this.orderRepository = orderRepository;
        this.staffCallRepository = staffCallRepository;
        this.orderSummaryMapper = orderSummaryMapper;
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(String authorization, OrderStatus status, PaymentStatus paymentStatus,
                                           LocalDate businessDate, String q) {
        StaffAccountEntity staff = boothInfoService.authenticate(jwtProvider.verify(authorization));
        if (staff.getBooth() == null) {
            throw new ForbiddenException();
        }
        Long boothId = staff.getBooth().getId();

        List<OrderEntity> orders =
                orderRepository.searchForDashboard(boothId, status, paymentStatus, businessDate, q, DASHBOARD_LIST_LIMIT);
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
