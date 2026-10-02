package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
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
    // 무한 누적 방지라는 원래 취지는 지키면서, 작은 행사 하루 물량은 넉넉히 담기게 30 → 500으로 올렸었다.
    // 2026-09-29 파일럿 전야 부하테스트로 500을 다시 올림: 자릿세가 세션 첫 메뉴 주문과 별도 주문 행으로 잡혀
    // (OrderWriter.saveSeatFeeOrder) 팀당 최소 2건(자릿세+메뉴)을 채번을 함께 쓰며, 30테이블·2시간 회전·
    // 18시~새벽 4시(5회전)에 팀당 추가주문 몇 건만 섞여도 부스 하루 합계가 500을 넘어(실측 재현: 600건 시점에
    // 테이블 화면에서 방금 만든 미결제 주문이 사라짐) 500이 파일럿 규모에서 안전하지 않다고 판단. MySQL
    // 누적 2300여 건에서도 O10 조회 p95 <1.3s(부하테스트 실측)라 이 정도 상향은 성능에 영향 없다.
    private static final Limit DASHBOARD_LIST_LIMIT = Limit.of(3000);
    /**
     * 주문현황 완료·취소 탭(status=DONE·CANCELED, activeSessionOnly 없이)은 최근 100건만 — 운영자가 거의 안 보는 이력 탭이고,
     * 그 탭을 보는 동안 5초마다 조회하므로 하루 누적이 그대로 실리면 저녁에 그 기기가 무거워진다. 그 이전 건은 "이전 주문 더 보기"
     * (beforeOrderId 커서 — 누르면 그 탭은 자동 갱신을 멈춘다, 프론트 OrderStatusPage)나 q(주문번호)로 찾는다.
     * activeSessionOnly(지금 세션 완료 — 추가 주문 배지·결제 모달)는 범위가 스스로 좁아 여기 해당하지 않는다
     */
    private static final Limit HISTORY_TAB_LIMIT = Limit.of(100);
    /** 커서 없는 첫 페이지 — JPQL에서 null 파라미터 비교 대신 어떤 주문보다도 늦은 값으로 둔다 */
    private static final LocalDateTime NO_CURSOR_CREATED_AT = LocalDateTime.of(9999, 1, 1, 0, 0);

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
     * activeSessionOnly=true: tableId가 있으면 그 테이블의, 없으면 부스 전체 테이블의 열린 세션 주문만.
     * 테이블 없이 부르는 쪽은 테이블-홈 카드다 — 예전엔 400이라 프론트가 영업일 주문 전체(상한까지)를 5초마다 받아 세션별로 걸렀고,
     * 저녁으로 갈수록 폴링이 무거워졌다. 지금 앉은 손님들 주문만 받으면 크기가 하루 누적과 무관하다.
     * activeSessionOnly=true에 businessDate를 생략하면 영업일 필터를 걸지 않는다(열린 세션의 주문 전부 — O24 대상과 같은 범위).
     */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(String authorization, OrderStatus status, PaymentStatus paymentStatus,
                                           LocalDate businessDate, String q, Long tableId, boolean activeSessionOnly) {
        return getDashboard(authorization, status, paymentStatus, businessDate, q, tableId, activeSessionOnly, null);
    }

    /**
     * beforeOrderId: 완료·취소 탭 "이전 주문 더 보기" 커서 — 그 주문보다 오래된(정렬 createdAt desc, id desc에서 뒤) 100건.
     * 기준 주문의 위치에서 이어 받으므로 offset처럼 앞 페이지를 읽고 버리지 않는다.
     * 이력 탭 조회(status=DONE·CANCELED, 다른 필터 없음)에서만 받는다 — 그 밖의 조합은 400.
     */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(String authorization, OrderStatus status, PaymentStatus paymentStatus,
                                           LocalDate businessDate, String q, Long tableId, boolean activeSessionOnly,
                                           Long beforeOrderId) {
        Long boothId = staffAuthenticator.authenticate(authorization).getBooth().getId();

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
        boolean history = (status == OrderStatus.DONE || status == OrderStatus.CANCELED) && !activeSessionOnly;
        Limit limit = status == OrderStatus.RECEIVED ? Limit.unlimited()
                : history ? HISTORY_TAB_LIMIT
                : DASHBOARD_LIST_LIMIT;
        // excludeHidden=true — 삭제(hidden=true) 처리된 취소 주문을 limit과 같은 쿼리에서 DB 단계부터 뺀다.
        // limit을 먼저 적용하고 나중에(Java에서) hidden을 지우면 hidden 행이 그 자리를 차지해 정상 취소 주문이
        // 밀려날 수 있어(실측됨) DB WHERE절에서 함께 처리한다. O18 매출 집계(SalesStatsService)는 이 메서드를
        // excludeHidden=false로 불러 hidden 여부와 무관하게 전부 보므로 정산·환불 데이터는 영향받지 않는다.
        // 주문현황 완료·취소 탭(필터 없는 이력 조회)만 id 먼저 자르는 쿼리로 — 같은 상한(HISTORY_TAB_LIMIT)이다
        boolean historyTab = history && paymentStatus == null && q == null && tableId == null;
        if (beforeOrderId != null && !historyTab) {
            throw new InvalidRequestException("beforeOrderId는 완료·취소 목록(status=DONE·CANCELED, 다른 필터 없음)에서만 쓸 수 있습니다.");
        }
        List<OrderEntity> orders = historyTab
                ? historyPage(boothId, status, effectiveDate, beforeOrderId)
                : orderRepository.searchForDashboard(
                        boothId, status, paymentStatus, effectiveDate, q, tableId, activeSessionOnly, true, limit);
        List<StaffCallEntity> calls = staffCallRepository.findUnackedByBoothId(boothId);

        return new DashboardResponse(
                orders.stream().map(orderSummaryMapper::toOrderSummary).toList(),
                calls.stream().map(this::toCallSummary).toList());
    }

    /**
     * 완료·취소 탭 한 페이지 — id를 먼저 HISTORY_TAB_LIMIT건만 자르고, 그 id들의 주문을 항목과 함께 읽는다.
     * searchForDashboard처럼 items 컬렉션 fetch와 Limit을 한 쿼리에 걸면 Hibernate가 DB에서 자르지 못하고
     * 조건에 맞는 행을 전부 읽은 뒤 메모리에서 자른다(HHH90003004) — 페이지를 넘길 때마다 하루치를 다시 읽게 된다.
     */
    private List<OrderEntity> historyPage(Long boothId, OrderStatus status, LocalDate businessDate, Long beforeOrderId) {
        LocalDateTime beforeCreatedAt = NO_CURSOR_CREATED_AT;
        long beforeId = Long.MAX_VALUE;
        if (beforeOrderId != null) {
            // items를 함께 읽는 findByIdAndBoothId 대신 findById(그래프 없음) — 정렬 키와 영업일만 필요하다
            OrderEntity cursor = orderRepository.findById(beforeOrderId)
                    .filter(o -> boothId.equals(o.getBoothId()))
                    .orElseThrow(() -> new NotFoundException("기준 주문을 찾을 수 없습니다."));
            beforeCreatedAt = cursor.getCreatedAt();
            beforeId = cursor.getId();
            // 영업일은 커서 주문 것을 쓴다 — 05:58에 받은 첫 페이지(전 영업일)의 더 보기를 06:01에 누르면 요청 시각 기준
            // 영업일(오늘)로는 커서보다 오래된 주문이 없어 "끝"처럼 보인다. 이어 보기는 첫 페이지와 같은 영업일이어야 한다
            businessDate = cursor.getBusinessDate();
        }
        List<Long> ids = orderRepository.findHistoryPageIds(
                boothId, status, businessDate, beforeCreatedAt, beforeId, HISTORY_TAB_LIMIT);
        return ids.isEmpty() ? List.of() : orderRepository.findWithItemsByIdIn(ids);
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
