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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O19 정산 CSV (명세서 §7.3, SettlementCsvService 상단 주석 참고) */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementCsvApiTests {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 1);

    @Autowired MockMvc mockMvc;
    @Autowired OrderRepository orderRepository;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired BoothRepository boothRepository;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long boothId;
    private String adminToken;
    private String staffToken;

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
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void generatesCsvWithBomHeaderAndFilename() throws Exception {
        newOrder(boothId, "T-1", 1, PaymentStatus.PAID, OrderStatus.DONE,
                new OrderItemEntity(1L, "김치전", 8_000, 2));

        MvcResult result = mockMvc.perform(request(adminToken, DATE))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"settlement_" + boothId + "_" + DATE + ".csv\""))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertEquals((byte) 0xEF, body[0]);
        assertEquals((byte) 0xBB, body[1]);
        assertEquals((byte) 0xBF, body[2]);

        String csv = new String(body, StandardCharsets.UTF_8).substring(1); // BOM(첫 글자) 제거
        String[] lines = csv.split("\r\n");
        assertEquals("주문번호,테이블,주문시각,메뉴명,수량,단가,금액,주문상태,결제상태,결제수단,"
                + "승인자,승인시각,취소자,취소시각,취소사유,환불처리자,환불처리시각", lines[0]);
        assertTrue(lines[1].startsWith("T1-1,T-1,2026-09-01 18:00:00,김치전,2,8000,16000,DONE,PAID,BANK_TRANSFER"));
    }

    @Test
    void excludesIndividuallyCanceledItemsButKeepsSiblingItems() throws Exception {
        Long orderId = newOrder(boothId, "T-1", 1, PaymentStatus.UNPAID, OrderStatus.RECEIVED,
                new OrderItemEntity(1L, "김치전", 8_000, 1), new OrderItemEntity(2L, "제로콜라", 5_000, 1));
        // 지연 로딩 프록시는 트랜잭션 밖이라 못 읽는다 — 메뉴명으로 직접 조회
        jdbcTemplate.update(
                "update order_item set canceled = true where order_id = ? and menu_name = ?",
                orderId, "제로콜라");

        String csv = fetchCsv(adminToken, DATE);
        assertTrue(csv.contains("김치전"));
        assertFalse(csv.contains("제로콜라"));
    }

    @Test
    void escapesFormulaInjectionAndCommasInFreeTextFields() throws Exception {
        Long orderId = newOrder(boothId, "T-1", 1, PaymentStatus.UNPAID, OrderStatus.CANCELED,
                new OrderItemEntity(1L, "메뉴, 특이", 1_000, 1));
        jdbcTemplate.update("update orders set cancel_reason = ?, canceled_by = ? where id = ?",
                "=SUM(A1:A9)", "staff, one", orderId);

        String csv = fetchCsv(adminToken, DATE);
        assertTrue(csv.contains("\"메뉴, 특이\""), csv);          // 쉼표 포함 → 큰따옴표로 감쌈
        assertTrue(csv.contains("'=SUM(A1:A9)"), csv);            // 수식 주입 방지 접두사
        assertTrue(csv.contains("\"staff, one\""), csv);
    }

    @Test
    void onlyIncludesRequestedBoothAndBusinessDate() throws Exception {
        newOrder(boothId, "T-1", 1, PaymentStatus.PAID, OrderStatus.DONE,
                new OrderItemEntity(1L, "포함됨", 1_000, 1));
        newOrder(boothId, "T-1", 2, PaymentStatus.PAID, OrderStatus.DONE,
                new OrderItemEntity(2L, "다른날", 1_000, 1)); // businessDate는 아래에서 다르게 덮어씀
        jdbcTemplate.update("update orders set business_date = ? where order_seq = 2",
                DATE.plusDays(1));
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        newOrder(otherBooth.getId(), "T-1", 1, PaymentStatus.PAID, OrderStatus.DONE,
                new OrderItemEntity(3L, "다른부스메뉴", 1_000, 1));

        String csv = fetchCsv(adminToken, DATE);
        assertTrue(csv.contains("포함됨"));
        assertFalse(csv.contains("다른날"));
        assertFalse(csv.contains("다른부스메뉴"));
    }

    @Test
    void rejectsStaffRole() throws Exception {
        mockMvc.perform(request(staffToken, DATE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void rejectsMissingAuthorization() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports/settlement.csv"))
                .andExpect(status().isUnauthorized());
    }

    private Long newOrder(Long orderBoothId, String tableLabel, int orderSeq,
            PaymentStatus paymentStatus, OrderStatus orderStatus, OrderItemEntity... items) {
        OrderEntity order = new OrderEntity(
                orderBoothId, null, "T" + orderSeq + "-1", DATE, orderSeq,
                "settle-" + orderBoothId + "-" + orderSeq, 0, false, tableLabel,
                LocalDateTime.of(2026, 9, 1, 18, 0));
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
                paymentStatus == PaymentStatus.PAID ? LocalDateTime.of(2026, 9, 1, 18, 5) : null,
                id);
        return id;
    }

    private String fetchCsv(String token, LocalDate date) throws Exception {
        byte[] body = mockMvc.perform(request(token, date))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return new String(body, StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String token, LocalDate date) {
        var req = get("/api/v1/admin/reports/settlement.csv").queryParam("date", date.toString());
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return req;
    }
}
