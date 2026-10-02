package com.boothlock.boothlock_server.settle.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SalesStatsApiTests {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate EXPLICIT_DATE = LocalDate.of(2026, 9, 1);
    @Autowired MockMvc mockMvc;
    @Autowired OrderRepository orderRepository;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired BoothRepository boothRepository;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired OrderNumberingService orderNumberingService;
    @Autowired JdbcTemplate jdbcTemplate;
    private Long boothId;
    private Long staffId;
    private StaffAccountEntity staff;
    private String token;
    private String staffToken;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();

        BoothEntity booth = boothRepository.save(new BoothEntity("매출 부스", "은행 1234", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        // O18은 ADMIN 전용 — 매출은 계좌 변경(O17)·환불 완료(O21)와 같은 민감도로 취급한다
        staff = staffAccountRepository.save(new StaffAccountEntity(
                booth, "sales-admin", hash, LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.ADMIN));
        boothId = booth.getId();
        staffId = staff.getId();
        token = jwtProvider.issue(staff, Instant.now());

        StaffAccountEntity nonAdminStaff = staffAccountRepository.save(new StaffAccountEntity(
                booth, "sales-staff", hash, LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.STAFF));
        staffToken = jwtProvider.issue(nonAdminStaff, Instant.now());
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void aggregatesOnlyAuthenticatedBoothAndRequestedBusinessDate() throws Exception {
        newOrder(boothId, EXPLICIT_DATE, 1, 10_000, PaymentStatus.PAID,
                PaymentMethod.BANK_TRANSFER, OrderStatus.RECEIVED,
                new OrderItemEntity(1L, "메뉴 1", 4_000, 1), new OrderItemEntity(2L, "메뉴 2", 3_000, 2));
        expectStats(request(token, EXPLICIT_DATE.toString()), 10_000, 10_000, 0, 1, 0, 0, 0, 0);
        newOrder(boothId, EXPLICIT_DATE, 2, 5_000, PaymentStatus.PAID, PaymentMethod.CASH, OrderStatus.CANCELED);
        newOrder(boothId, EXPLICIT_DATE, 3, 9_000, PaymentStatus.UNPAID, null, OrderStatus.RECEIVED);
        newOrder(boothId, EXPLICIT_DATE, 4, 4_000, PaymentStatus.REFUND_NEEDED,
                PaymentMethod.BANK_TRANSFER, OrderStatus.CANCELED);
        newOrder(boothId, EXPLICIT_DATE, 5, 3_000, PaymentStatus.REFUNDED, PaymentMethod.CASH, OrderStatus.CANCELED);
        newOrder(boothId, EXPLICIT_DATE.plusDays(1), 1, 7_000, PaymentStatus.PAID,
                PaymentMethod.CASH, OrderStatus.RECEIVED);
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        newOrder(otherBooth.getId(), EXPLICIT_DATE, 1, 8_000, PaymentStatus.PAID,
                PaymentMethod.CASH, OrderStatus.RECEIVED);
        expectStats(request(token, EXPLICIT_DATE.toString()),
                15_000, 10_000, 5_000, 2, 1, 4_000, 1, 3_000);
    }
    @Test
    void countsHiddenCanceledOrdersInRefundNeededSummary() throws Exception {
        // 취소 주문 삭제(hidden=true)는 주문현황 목록에서만 빠져야 하고, O18 매출 집계(환불필요 건수·금액)에는
        // 영향을 주면 안 된다 — searchForDashboard를 excludeHidden=false로 불러야 하는 이유
        Long orderId = newOrder(boothId, EXPLICIT_DATE, 1, 4_000, PaymentStatus.REFUND_NEEDED,
                PaymentMethod.BANK_TRANSFER, OrderStatus.CANCELED);
        jdbcTemplate.update("update orders set hidden = true where id = ?", orderId);
        expectStats(request(token, EXPLICIT_DATE.toString()), 0, 0, 0, 0, 1, 4_000, 0, 0);
    }
    @Test
    void aggregatesItemSalesByTypeAndNameForPaidOrdersOnly() throws Exception {
        // 결제완료 주문 2건 — 같은 메뉴명은 단가가 달라도 합산, 개별 취소 항목은 제외
        OrderItemEntity canceledFries = new OrderItemEntity(2L, "감튀", 5_000, 4);
        canceledFries.cancel();
        newOrder(boothId, EXPLICIT_DATE, 1, 41_000, PaymentStatus.PAID,
                PaymentMethod.BANK_TRANSFER, OrderStatus.DONE,
                new OrderItemEntity(1L, "치킨", 20_000, 1), new OrderItemEntity(2L, "감튀", 5_000, 2),
                canceledFries, OrderItemEntity.seatFee(3_000, 2), OrderItemEntity.extra(9L, "쿠폰", -2_000, 1));
        newOrder(boothId, EXPLICIT_DATE, 2, 18_000, PaymentStatus.PAID, PaymentMethod.CASH, OrderStatus.DONE,
                new OrderItemEntity(1L, "치킨", 18_000, 1));
        // 미결제·환불 대상 주문의 항목은 판매로 세지 않는다
        newOrder(boothId, EXPLICIT_DATE, 3, 50_000, PaymentStatus.UNPAID, null, OrderStatus.RECEIVED,
                new OrderItemEntity(3L, "떡볶이", 10_000, 5));
        newOrder(boothId, EXPLICIT_DATE, 4, 5_000, PaymentStatus.REFUND_NEEDED,
                PaymentMethod.CASH, OrderStatus.CANCELED, new OrderItemEntity(2L, "감튀", 5_000, 1));

        // 정렬: 종류(MENU→SEAT_FEE→EXTRA) 안에서 수량 내림차순, 같으면 이름순
        request(token, EXPLICIT_DATE.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value(EXPLICIT_DATE.toString()))
                .andExpect(jsonPath("$.itemSales", hasSize(4)))
                .andExpect(jsonPath("$.itemSales[0].itemType").value("MENU"))
                .andExpect(jsonPath("$.itemSales[0].name").value("감튀"))
                .andExpect(jsonPath("$.itemSales[0].qty").value(2))
                .andExpect(jsonPath("$.itemSales[0].amount").value(10_000))
                .andExpect(jsonPath("$.itemSales[1].itemType").value("MENU"))
                .andExpect(jsonPath("$.itemSales[1].name").value("치킨"))
                .andExpect(jsonPath("$.itemSales[1].qty").value(2))
                .andExpect(jsonPath("$.itemSales[1].amount").value(38_000))
                .andExpect(jsonPath("$.itemSales[2].itemType").value("SEAT_FEE"))
                .andExpect(jsonPath("$.itemSales[2].name").value("자릿세"))
                .andExpect(jsonPath("$.itemSales[2].qty").value(2))
                .andExpect(jsonPath("$.itemSales[2].amount").value(6_000))
                .andExpect(jsonPath("$.itemSales[3].itemType").value("EXTRA"))
                .andExpect(jsonPath("$.itemSales[3].name").value("쿠폰"))
                .andExpect(jsonPath("$.itemSales[3].qty").value(1))
                .andExpect(jsonPath("$.itemSales[3].amount").value(-2_000));
    }
    @Test
    void returnsZeroSummaryWhenRequestedDateHasNoOrders() throws Exception {
        expectStats(request(token, EXPLICIT_DATE.toString()), 0, 0, 0, 0, 0, 0, 0, 0);
        request(token, EXPLICIT_DATE.toString()).andExpect(jsonPath("$.itemSales", hasSize(0)));
    }
    @Test
    void usesCurrentKstBusinessDateWhenDateIsOmitted() throws Exception {
        LocalDate currentBusinessDate = orderNumberingService.businessDateOf(LocalDateTime.now(KST));
        newOrder(boothId, currentBusinessDate, 1, 12_000, PaymentStatus.PAID,
                PaymentMethod.CASH, OrderStatus.RECEIVED);
        expectStats(request(token, null), 12_000, 0, 12_000, 1, 0, 0, 0, 0);
        request(token, null).andExpect(jsonPath("$.businessDate").value(currentBusinessDate.toString()));
    }
    @Test
    void rejectsInvalidDate() throws Exception {
        request(token, "2026-13-40")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
    @Test
    void rejectsSalesStatsForStaffRole() throws Exception {
        request(staffToken, EXPLICIT_DATE.toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
    @Test
    void rejectsRequestWithoutAuthorizationHeader() throws Exception { expectUnauthorized(null); }
    @Test
    void rejectsExpiredToken() throws Exception {
        String expiredToken = jwtProvider.issue(staff, Instant.now().minusSeconds(21 * 60 * 60));
        expectUnauthorized(expiredToken);
    }
    @Test
    void rejectsInactiveAccount() throws Exception {
        jdbcTemplate.update("update staff_account set active = false where id = ?", staffId);
        expectUnauthorized(token);
    }
    @Test
    void rejectsTokenIssuedBeforePasswordChange() throws Exception {
        jdbcTemplate.update(
                "update staff_account set password_changed_at = ? where id = ?",
                LocalDateTime.of(2026, 9, 2, 12, 0), staffId);
        expectUnauthorized(token);
    }
    private Long newOrder(Long orderBoothId, LocalDate businessDate, int orderSeq, int totalAmount,
            PaymentStatus paymentStatus, PaymentMethod paymentMethod, OrderStatus orderStatus,
            OrderItemEntity... items) {
        OrderEntity order = new OrderEntity(
                orderBoothId, null, "S-" + orderSeq, businessDate, orderSeq,
                "sales-" + orderBoothId + "-" + businessDate + "-" + orderSeq,
                totalAmount, false,
                LocalDateTime.of(businessDate.getYear(), businessDate.getMonthValue(),
                        businessDate.getDayOfMonth(), 12, 0));
        for (OrderItemEntity item : items) {
            order.addItem(item);
        }
        orderRepository.save(order);
        jdbcTemplate.update(
                "update orders set payment_status = ?, payment_method = ?, status = ? where id = ?",
                paymentStatus.name(), paymentMethod == null ? null : paymentMethod.name(),
                orderStatus.name(), order.getId());
        return order.getId();
    }
    private void expectStats(ResultActions result, long total, long bank, long cash, long paidCount,
            long neededCount, long neededAmount, long refundedCount, long refundedAmount) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSales").value(total))
                .andExpect(jsonPath("$.byMethod.BANK_TRANSFER").value(bank))
                .andExpect(jsonPath("$.byMethod.CASH").value(cash))
                .andExpect(jsonPath("$.paidOrderCount").value(paidCount))
                .andExpect(jsonPath("$.refundNeeded.count").value(neededCount))
                .andExpect(jsonPath("$.refundNeeded.amount").value(neededAmount))
                .andExpect(jsonPath("$.refunded.count").value(refundedCount))
                .andExpect(jsonPath("$.refunded.amount").value(refundedAmount));
    }
    private ResultActions request(String accessToken, String date) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/admin/stats/sales");
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        if (date != null) request.queryParam("date", date);
        return mockMvc.perform(request);
    }
    private void expectUnauthorized(String accessToken) throws Exception {
        request(accessToken, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
