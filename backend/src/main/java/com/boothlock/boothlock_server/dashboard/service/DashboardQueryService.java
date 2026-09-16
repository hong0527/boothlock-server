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
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;

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
    // MVP: 완료/취소 탭(그리고 상태 필터 없는 조회)은 최신 N건만 — 그 이전 주문은 q(주문번호 검색)로 찾는다.
    // 진행중(RECEIVED)은 처리 안 된 주문이 뒤로 밀려 안 보이면 안 되므로 제한 없음.
    // 상태 필터 없는 조회는 테이블-홈/결제 모달이 하루치 테이블별 항목·합계를 집계하는 데도 쓰여서
    // (TableOrderContext.tsx, PaymentModal.tsx) 하루 주문이 이 값을 넘으면 그 집계가 조용히 누락된다 —
    // 무한 누적 방지라는 원래 취지는 지키면서, 작은 행사 하루 물량은 넉넉히 담기게 30 → 500으로 올린다.
    private static final Limit DASHBOARD_LIST_LIMIT = Limit.of(500);

    private final OrderRepository orderRepository;
    private final StaffCallRepository staffCallRepository;
    private final OrderSummaryMapper orderSummaryMapper;
    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final TableRepository tableRepository;

    public DashboardQueryService(OrderRepository orderRepository, StaffCallRepository staffCallRepository,
            OrderSummaryMapper orderSummaryMapper, BoothJwtProvider jwtProvider, BoothInfoService boothInfoService,
            TableRepository tableRepository) {
        this.orderRepository = orderRepository;
        this.staffCallRepository = staffCallRepository;
        this.orderSummaryMapper = orderSummaryMapper;
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.tableRepository = tableRepository;
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(String authorization, OrderStatus status, PaymentStatus paymentStatus,
                                           LocalDate businessDate, String q, Long tableId) {
        StaffAccountEntity staff = boothInfoService.authenticate(jwtProvider.verify(authorization));
        if (staff.getBooth() == null) {
            throw new ForbiddenException();
        }
        Long boothId = staff.getBooth().getId();

        if (tableId != null) {
            // 타 부스 테이블이면 존재를 숨긴다 (§1.2 존재 은닉과 동일 원칙)
            var table = tableRepository.findById(tableId)
                    .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));
            if (!table.getBooth().getId().equals(boothId)) {
                throw new NotFoundException("테이블을 찾을 수 없습니다.");
            }
        }

        Limit limit = status == OrderStatus.RECEIVED ? Limit.unlimited() : DASHBOARD_LIST_LIMIT;
        List<OrderEntity> orders =
                orderRepository.searchForDashboard(boothId, status, paymentStatus, businessDate, q, tableId, limit);
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
