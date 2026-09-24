package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/** O10 실시간 대시보드 — 주문 목록 + 미확인 호출을 한 번에 조회 (명세서 O10) */
@Service
public class DashboardQueryService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    /** JVM 기본 시간대에 기대지 않는다 — java -jar 배포에는 -Duser.timezone이 붙지 않아 UTC 서버에서 9시간 어긋난다 */
    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");
    // MVP: 완료/취소 탭(그리고 상태 필터 없는 조회)은 최신 N건만 — 그 이전 주문은 q(주문번호 검색)로 찾는다.
    // 진행중(RECEIVED)은 처리 안 된 주문이 뒤로 밀려 안 보이면 안 되므로 제한 없음.
    // 상태 필터 없는 조회는 테이블-홈/결제 모달이 하루치 테이블별 항목·합계를 집계하는 데도 쓰여서
    // (TableOrderContext.tsx, PaymentModal.tsx) 하루 주문이 이 값을 넘으면 그 집계가 조용히 누락된다 —
    // 무한 누적 방지라는 원래 취지는 지키면서, 작은 행사 하루 물량은 넉넉히 담기게 30 → 500으로 올린다.
    private static final Limit DASHBOARD_LIST_LIMIT = Limit.of(500);

    private final OrderRepository orderRepository;
    private final StaffCallRepository staffCallRepository;
    private final OrderSummaryMapper orderSummaryMapper;
    private final BoothStaffAuthenticator staffAuthenticator;
    private final BoothTableLookup tableLookup;
    private final OrderNumberingService numberingService;

    public DashboardQueryService(OrderRepository orderRepository, StaffCallRepository staffCallRepository,
            OrderSummaryMapper orderSummaryMapper, BoothStaffAuthenticator staffAuthenticator,
            BoothTableLookup tableLookup, OrderNumberingService numberingService) {
        this.orderRepository = orderRepository;
        this.staffCallRepository = staffCallRepository;
        this.orderSummaryMapper = orderSummaryMapper;
        this.staffAuthenticator = staffAuthenticator;
        this.tableLookup = tableLookup;
        this.numberingService = numberingService;
    }

    /**
     * 부스는 JWT로만 정한다 — STAFF·ADMIN 허용, 무토큰 401, SUPER_ADMIN 403 (명세서 §1.2·§7-21).
     * activeSessionOnly는 tableId의 하위 옵션이다 — 테이블 없이 "활성 세션만"은 뜻이 없으므로 조용히 무시하지 않고 400.
     * activeSessionOnly=true에 businessDate를 생략하면 영업일 필터를 걸지 않는다(열린 세션의 주문 전부 — O24 대상과 같은 범위).
     */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(String authorization, OrderStatus status, PaymentStatus paymentStatus,
                                           LocalDate businessDate, String q, Long tableId, boolean activeSessionOnly) {
        Long boothId = staffAuthenticator.authenticate(authorization).getBooth().getId();

        if (activeSessionOnly && tableId == null) {
            throw new InvalidRequestException("activeSessionOnly는 tableId와 함께 써야 합니다.");
        }
        if (tableId != null) {
            // 필터 결과가 빈 목록인 것과 "그런 테이블 없음"을 구분한다 — 미존재·타 부스·삭제 테이블은 404 (명세서 O10)
            tableLookup.requireTableOfBooth(tableId, boothId);
        }

        // activeSessionOnly(결제 모달)는 businessDate를 생략하면 영업일로 거르지 않는다 — O24 일괄 입금 대상
        // (TablePaymentOrderRepository: 열린 세션의 미결제 전부, 영업일 무관)과 같은 집합이어야 한다.
        // 영업일로 거르면 06:00 경계를 넘긴 열린 세션에서 전 영업일 미결제가 모달 합계(expectedTotal)에서 빠지고,
        // 서버 합계에는 남아 O24가 영원히 409가 된다. 열린 세션 하나의 주문이라 범위가 스스로 좁다
        LocalDate effectiveDate = activeSessionOnly && businessDate == null
                ? null
                : resolveBusinessDate(businessDate, LocalDateTime.now(KST_ZONE));
        Limit limit = status == OrderStatus.RECEIVED ? Limit.unlimited() : DASHBOARD_LIST_LIMIT;
        // excludeHidden=true — 삭제(hidden=true) 처리된 취소 주문을 limit(500건)과 같은 쿼리에서 DB 단계부터 뺀다.
        // limit을 먼저 적용하고 나중에(Java에서) hidden을 지우면 hidden 행이 그 자리를 차지해 정상 취소 주문이
        // 밀려날 수 있어(실측됨) DB WHERE절에서 함께 처리한다. O18 매출 집계(SalesStatsService)는 이 메서드를
        // excludeHidden=false로 불러 hidden 여부와 무관하게 전부 보므로 정산·환불 데이터는 영향받지 않는다.
        List<OrderEntity> orders = orderRepository.searchForDashboard(
                boothId, status, paymentStatus, effectiveDate, q, tableId, activeSessionOnly, true, limit);
        List<StaffCallEntity> calls = staffCallRepository.findUnackedByBoothId(boothId);

        return new DashboardResponse(
                orders.stream().map(orderSummaryMapper::toOrderSummary).toList(),
                calls.stream().map(this::toCallSummary).toList());
    }

    /**
     * businessDate를 생략하면 "현재 영업일"(06:00 경계, 명세서 §2) — 달력 날짜가 아니다.
     * 프론트가 달력 날짜(todayKst)를 보내면 00~06시에 전날 영업일 주문이 화면에서 사라지므로, 프론트는 파라미터를 빼고 이 기본값을 쓴다.
     * 전 영업일 조회는 의도적으로 열지 않는다 — 무제한 누적 조회는 O18 정산·q 검색으로 대신한다.
     */
    LocalDate resolveBusinessDate(LocalDate requested, LocalDateTime nowKst) {
        return requested != null ? requested : numberingService.businessDateOf(nowKst);
    }

    private DashboardResponse.CallSummary toCallSummary(StaffCallEntity c) {
        return new DashboardResponse.CallSummary(
                c.getId(), c.getSession().getTable().getLabel(), c.getReason().name(), atKst(c.getCreatedAt()));
    }

    private OffsetDateTime atKst(LocalDateTime dt) {
        return dt == null ? null : dt.atOffset(KST);
    }
}
