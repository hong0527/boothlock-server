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
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O19 정산 CSV (명세서 §7.3, SettlementCsvService 상단 주석 참고).
 * v0.6.5부터 조회 기준 = PAID 확정 매출 + approvedAt(입금 확인 시각) 구간 [start, end).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementCsvApiTests {

    private static final DateTimeFormatter PARAM_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired MockMvc mockMvc;
    @Autowired OrderRepository orderRepository;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired BoothRepository boothRepository;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long boothId;
    private String adminToken;
    private String staffToken;
    private int orderSeq;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();

        BoothEntity booth = boothRepository.save(new BoothEntity("정산 부스", "은행 1234", null));
        boothId = booth.getId();
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        StaffAccountEntity admin = staffAccountRepository.save(new StaffAccountEntity(
                booth, "settle-admin", hash, LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.ADMIN));
        adminToken = jwtProvider.issue(admin, Instant.now());
        StaffAccountEntity staff = staffAccountRepository.save(new StaffAccountEntity(
                booth, "settle-staff", hash, LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.STAFF));
        staffToken = jwtProvider.issue(staff, Instant.now());
        orderSeq = 0;
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void generatesCsvWithBomHeaderAndFilenameForApprovedRange() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 6, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 5, 50), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 6, 5),
                new OrderItemEntity(1L, "김치전", 8_000, 2));

        MvcResult result = mockMvc.perform(request(adminToken, start, end))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"settlement_" + boothId + "_20260901T060000_20260901T120000.csv\""))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertEquals((byte) 0xEF, body[0]);
        assertEquals((byte) 0xBB, body[1]);
        assertEquals((byte) 0xBF, body[2]);

        String csv = new String(body, StandardCharsets.UTF_8).substring(1); // BOM(첫 글자) 제거
        String[] lines = csv.split("\r\n");
        assertEquals("주문번호,테이블,주문시각,메뉴명,수량,단가,금액,주문상태,결제상태,결제수단,"
                + "승인자,승인시각,취소자,취소시각,취소사유,환불처리자,환불처리시각", lines[0]);
        assertTrue(lines[1].startsWith("T1-1,T-1,2026-09-01 05:50:00,김치전,2,8000,16000,DONE,PAID,BANK_TRANSFER"));
        assertTrue(lines[1].contains("2026-09-01 06:05:00")); // 승인시각(approvedAt) 컬럼
    }

    @Test
    void excludesUnpaidOrders() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.UNPAID, OrderStatus.RECEIVED,
                null, new OrderItemEntity(1L, "미입금메뉴", 5_000, 1));

        String csv = fetchCsv(adminToken, start, end);
        assertFalse(csv.contains("미입금메뉴"));
    }

    @Test
    void excludesOrdersApprovedOutsideRange() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 6, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 5, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 5, 59, 59), new OrderItemEntity(1L, "구간이전", 1_000, 1));
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 12, 30), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 12, 0, 1), new OrderItemEntity(2L, "구간이후", 1_000, 1));

        String csv = fetchCsv(adminToken, start, end);
        assertFalse(csv.contains("구간이전"));
        assertFalse(csv.contains("구간이후"));
    }

    @Test
    void includesOrderWhenApprovedAtEqualsStartBoundary() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 6, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 5, 55), PaymentStatus.PAID, OrderStatus.DONE,
                start, new OrderItemEntity(1L, "시작경계포함", 1_000, 1));

        String csv = fetchCsv(adminToken, start, end);
        assertTrue(csv.contains("시작경계포함"));
    }

    @Test
    void excludesOrderWhenApprovedAtEqualsEndBoundary() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 6, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 11, 55), PaymentStatus.PAID, OrderStatus.DONE,
                end, new OrderItemEntity(1L, "마감경계제외", 1_000, 1));

        String csv = fetchCsv(adminToken, start, end);
        assertFalse(csv.contains("마감경계제외"));
    }

    @Test
    void handlesMidnightCrossingRange() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 30, 22, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 1, 3, 0, 0);
        // 2026-09-30 21:50 생성 → 2026-10-01 02:10 결제 완료 — 자정을 넘겨 승인된 주문도 구간에 포함돼야 한다
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 30, 21, 50), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 10, 1, 2, 10), new OrderItemEntity(1L, "자정넘김포함", 1_000, 1));
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 30, 19, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 30, 21, 0), new OrderItemEntity(2L, "구간이전제외", 1_000, 1));
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 10, 1, 3, 30), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 10, 1, 4, 0), new OrderItemEntity(3L, "구간이후제외", 1_000, 1));

        String csv = fetchCsv(adminToken, start, end);
        assertTrue(csv.contains("자정넘김포함"));
        assertFalse(csv.contains("구간이전제외"));
        assertFalse(csv.contains("구간이후제외"));
    }

    @Test
    void excludesIndividuallyCanceledItemsButKeepsSiblingItems() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        Long orderId = newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID,
                OrderStatus.DONE, LocalDateTime.of(2026, 9, 1, 10, 5),
                new OrderItemEntity(1L, "김치전", 8_000, 1), new OrderItemEntity(2L, "제로콜라", 5_000, 1));
        // 지연 로딩 프록시는 트랜잭션 밖이라 못 읽는다 — 메뉴명으로 직접 조회
        jdbcTemplate.update(
                "update order_item set canceled = true where order_id = ? and menu_name = ?",
                orderId, "제로콜라");

        String csv = fetchCsv(adminToken, start, end);
        assertTrue(csv.contains("김치전"));
        assertFalse(csv.contains("제로콜라"));
    }

    @Test
    void escapesFormulaInjectionAndCommasInFreeTextFields() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        Long orderId = newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID,
                OrderStatus.DONE, LocalDateTime.of(2026, 9, 1, 10, 5),
                new OrderItemEntity(1L, "메뉴, 특이", 1_000, 1));
        jdbcTemplate.update("update orders set approved_by = ? where id = ?",
                "=SUM(A1:A9)", orderId);

        String csv = fetchCsv(adminToken, start, end);
        assertTrue(csv.contains("\"메뉴, 특이\""), csv);          // 쉼표 포함 → 큰따옴표로 감쌈
        assertTrue(csv.contains("'=SUM(A1:A9)"), csv);            // 수식 주입 방지 접두사
    }

    @Test
    void onlyIncludesRequestedBooth() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 10, 5), new OrderItemEntity(1L, "포함됨", 1_000, 1));

        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        OrderEntity otherOrder = new OrderEntity(
                otherBooth.getId(), null, "T99-1", LocalDate.of(2026, 9, 1), 99,
                "settle-other-99", 0, false, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0));
        otherOrder.addItem(new OrderItemEntity(3L, "다른부스메뉴", 1_000, 1));
        Long otherOrderId = orderRepository.save(otherOrder).getId();
        jdbcTemplate.update(
                "update orders set payment_status = 'PAID', payment_method = 'BANK_TRANSFER', status = 'DONE', "
                        + "approved_by = 'settle-admin', approved_at = ? where id = ?",
                LocalDateTime.of(2026, 9, 1, 10, 5), otherOrderId);

        String csv = fetchCsv(adminToken, start, end);
        assertTrue(csv.contains("포함됨"));
        assertFalse(csv.contains("다른부스메뉴"));
    }

    @Test
    void rejectsWhenStartAtNotBeforeEndAt() throws Exception {
        LocalDateTime same = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
        mockMvc.perform(request(adminToken, same, same))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(request(adminToken, same, same.minusMinutes(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsMissingRangeParams() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports/settlement.csv")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsStaffRole() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        mockMvc.perform(request(staffToken, start, end))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void rejectsMissingAuthorization() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        mockMvc.perform(get("/api/v1/admin/reports/settlement.csv")
                        .queryParam("startAt", start.format(PARAM_FORMAT))
                        .queryParam("endAt", end.format(PARAM_FORMAT)))
                .andExpect(status().isUnauthorized());
    }

    private Long newOrder(Long orderBoothId, String tableLabel, LocalDateTime createdAt,
            PaymentStatus paymentStatus, OrderStatus orderStatus, LocalDateTime approvedAt,
            OrderItemEntity... items) {
        orderSeq++;
        OrderEntity order = new OrderEntity(
                orderBoothId, null, "T" + orderSeq + "-1", createdAt.toLocalDate(), orderSeq,
                "settle-" + orderBoothId + "-" + orderSeq, 0, false, tableLabel, createdAt);
        for (OrderItemEntity item : items) {
            order.addItem(item);
        }
        Long id = orderRepository.save(order).getId();
        jdbcTemplate.update(
                "update orders set payment_status = ?, payment_method = ?, status = ?, "
                        + "approved_by = ?, approved_at = ? where id = ?",
                paymentStatus.name(), paymentStatus == PaymentStatus.PAID ? "BANK_TRANSFER" : null,
                orderStatus.name(),
                paymentStatus == PaymentStatus.PAID ? "settle-admin" : null,
                approvedAt,
                id);
        return id;
    }

    private String fetchCsv(String token, LocalDateTime start, LocalDateTime end) throws Exception {
        byte[] body = mockMvc.perform(request(token, start, end))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return new String(body, StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            String token, LocalDateTime start, LocalDateTime end) {
        var req = get("/api/v1/admin/reports/settlement.csv")
                .queryParam("startAt", start.format(PARAM_FORMAT))
                .queryParam("endAt", end.format(PARAM_FORMAT));
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return req;
    }
}
