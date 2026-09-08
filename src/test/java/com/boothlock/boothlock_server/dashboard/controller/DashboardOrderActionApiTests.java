package com.boothlock.boothlock_server.dashboard.controller;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O11 입금 확인·O12 완료 처리·O13 운영자 취소 API 테스트 (명세서 O11·O12·O13) */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardOrderActionApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired OrderRepository orderRepository;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long boothId;
    private StaffAccountEntity staff;
    private String token;
    private String adminToken;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();

        BoothEntity booth = boothRepository.save(new BoothEntity("결제 부스", "카카오뱅크 1234", null));
        boothId = booth.getId();
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staff = staffAccountRepository.save(new StaffAccountEntity(
                booth, "dashboard-staff", hash, LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.STAFF));
        token = jwtProvider.issue(staff, Instant.now());

        StaffAccountEntity admin = staffAccountRepository.save(new StaffAccountEntity(
                booth, "dashboard-admin", hash, LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.ADMIN));
        adminToken = jwtProvider.issue(admin, Instant.now());
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    private Long newOrder(Long boothId, int orderSeq) {
        OrderEntity order = new OrderEntity(
                boothId, null, "A3-" + orderSeq, LocalDate.of(2026, 9, 1),
                orderSeq, "idem-" + boothId + "-" + orderSeq, 16000, false,
                LocalDateTime.of(2026, 9, 1, 18, 0));
        order.addItem(new OrderItemEntity(3L, "김치전", 8000, 2));
        return orderRepository.save(order).getId();
    }

    private void setPaymentStatus(Long orderId, PaymentStatus status) {
        jdbcTemplate.update("update orders set payment_status = ? where id = ?", status.name(), orderId);
    }

    private void setOrderStatus(Long orderId, OrderStatus status) {
        jdbcTemplate.update("update orders set status = ? where id = ?", status.name(), orderId);
    }

    // ── O11 입금 확인 ────────────────────────────────────────

    @Test
    void confirmsPaymentAndRecordsApprover() throws Exception {
        Long orderId = newOrder(boothId, 1);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/payment", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.paymentMethod").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$.approvedBy").value("dashboard-staff"))
                .andExpect(jsonPath("$.approvedAt").exists());

        OrderEntity saved = orderRepository.findById(orderId).orElseThrow();
        assertEquals(PaymentStatus.PAID, saved.getPaymentStatus());
        assertEquals("dashboard-staff", saved.getApprovedBy());
        assertNotNull(saved.getApprovedAt());
    }

    @Test
    void rejectsConfirmPaymentOnAlreadyPaidOrder() throws Exception {
        Long orderId = newOrder(boothId, 2);
        setPaymentStatus(orderId, PaymentStatus.PAID);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/payment", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_PAID"));
    }

    @Test
    void rejectsConfirmPaymentForUnknownOrder() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/payment", 999999L)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void hidesOtherBoothOrderAsNotFound() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "국민은행 5678", null));
        Long otherOrderId = newOrder(otherBooth.getId(), 1);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/payment", otherOrderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsConfirmPaymentOnCanceledOrder() throws Exception {
        Long orderId = newOrder(boothId, 12);
        setOrderStatus(orderId, OrderStatus.CANCELED);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/payment", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertEquals(PaymentStatus.UNPAID, orderRepository.findById(orderId).orElseThrow().getPaymentStatus());
    }

    @Test
    void rejectsMissingMethod() throws Exception {
        Long orderId = newOrder(boothId, 3);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/payment", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertEquals(PaymentStatus.UNPAID, orderRepository.findById(orderId).orElseThrow().getPaymentStatus());
    }

    @Test
    void rejectsInvalidMethodValue() throws Exception {
        Long orderId = newOrder(boothId, 4);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/payment", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CARD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsConfirmPaymentWithoutAuthorization() throws Exception {
        Long orderId = newOrder(boothId, 5);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/payment", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void serializesConcurrentPaymentConfirmationsForSameOrder() throws Exception {
        Long orderId = newOrder(boothId, 6);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Integer> attempt = () -> {
            ready.countDown();
            start.await();
            return orderRepository.markPaid(orderId, boothId, PaymentMethod.CASH,
                    "staff-" + Thread.currentThread().threadId(), LocalDateTime.now());
        };

        List<Future<Integer>> futures = List.of(executor.submit(attempt), executor.submit(attempt));
        ready.await();
        start.countDown();
        List<Integer> results = futures.stream().map(f -> {
            try {
                return f.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
        executor.shutdown();

        assertEquals(1, results.stream().filter(r -> r == 1).count());
        assertEquals(1, results.stream().filter(r -> r == 0).count());
    }

    // ── O12 완료 처리 ────────────────────────────────────────

    @Test
    void completesReceivedOrder() throws Exception {
        Long orderId = newOrder(boothId, 7);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/complete", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        assertEquals(OrderStatus.DONE, orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    @Test
    void completesEvenWhenUnpaid() throws Exception {
        Long orderId = newOrder(boothId, 8);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/complete", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"));
    }

    @Test
    void rejectsCompleteOnAlreadyDoneOrder() throws Exception {
        Long orderId = newOrder(boothId, 9);
        setOrderStatus(orderId, OrderStatus.DONE);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/complete", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsCompleteOnCanceledOrder() throws Exception {
        Long orderId = newOrder(boothId, 10);
        setOrderStatus(orderId, OrderStatus.CANCELED);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/complete", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsCompleteForUnknownOrder() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/complete", 999999L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsCompleteWithoutAuthorization() throws Exception {
        Long orderId = newOrder(boothId, 11);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/complete", orderId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ── O13 운영자 취소 ──────────────────────────────────────

    @Test
    void cancelsOrderAndRecordsStaffAsCanceler() throws Exception {
        Long orderId = newOrder(boothId, 13);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"재료 소진\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.cancelReason").value("재료 소진"))
                .andExpect(jsonPath("$.canceledBy").value("dashboard-staff"))
                .andExpect(jsonPath("$.canceledAt").exists());

        OrderEntity saved = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CANCELED, saved.getStatus());
        assertEquals("dashboard-staff", saved.getCanceledBy());   // 소비자 취소의 "CUSTOMER"와 구분
    }

    @Test
    void cancelingPaidOrderMovesToRefundNeeded() throws Exception {
        Long orderId = newOrder(boothId, 14);
        setPaymentStatus(orderId, PaymentStatus.PAID);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"손님 요청\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("REFUND_NEEDED"));

        assertEquals(PaymentStatus.REFUND_NEEDED, orderRepository.findById(orderId).orElseThrow().getPaymentStatus());
    }

    @Test
    void cancelingUnpaidOrderKeepsUnpaid() throws Exception {
        Long orderId = newOrder(boothId, 15);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"손님 요청\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"));
    }

    @Test
    void staffCanCancelDoneOrder() throws Exception {
        // 소비자(C5)는 RECEIVED만 취소 가능하지만 운영자는 전달 완료분도 취소한다 (명세서 O13)
        Long orderId = newOrder(boothId, 16);
        setOrderStatus(orderId, OrderStatus.DONE);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"음식 문제\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void rejectsCancelOnAlreadyCanceledOrder() throws Exception {
        Long orderId = newOrder(boothId, 17);
        setOrderStatus(orderId, OrderStatus.CANCELED);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"중복 시도\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsCancelForUnknownOrder() throws Exception {
        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", 999999L)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"손님 요청\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void hidesOtherBoothOrderAsNotFoundOnCancel() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "국민은행 5678", null));
        Long otherOrderId = newOrder(otherBooth.getId(), 1);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", otherOrderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"남의 부스\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsMissingReason() throws Exception {
        Long orderId = newOrder(boothId, 18);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertEquals(OrderStatus.RECEIVED, orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    @Test
    void rejectsReasonOver100Chars() throws Exception {
        Long orderId = newOrder(boothId, 19);
        String tooLong = "가".repeat(101);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsCancelWithoutAuthorization() throws Exception {
        Long orderId = newOrder(boothId, 20);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"손님 요청\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ── O21 환불 완료 ────────────────────────────────────────

    private Long newRefundNeededOrder(int orderSeq) {
        Long orderId = newOrder(boothId, orderSeq);
        setPaymentStatus(orderId, PaymentStatus.REFUND_NEEDED);
        setOrderStatus(orderId, OrderStatus.CANCELED);
        return orderId;
    }

    @Test
    void refundDoneMarksRefundedAndRecordsHandler() throws Exception {
        Long orderId = newRefundNeededOrder(21);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/refund-done", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedBy").value("dashboard-admin"))
                .andExpect(jsonPath("$.refundedAt").exists());

        OrderEntity saved = orderRepository.findById(orderId).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, saved.getPaymentStatus());
        assertEquals("dashboard-admin", saved.getRefundedBy());
        assertNotNull(saved.getRefundedAt());
    }

    @Test
    void rejectsRefundDoneForStaffRole() throws Exception {
        // ADMIN 전용 — STAFF가 취소부터 환불완료까지 혼자 끝내는 걸 막는다 (PR #27 리뷰 요구사항)
        Long orderId = newRefundNeededOrder(22);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/refund-done", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertEquals(PaymentStatus.REFUND_NEEDED, orderRepository.findById(orderId).orElseThrow().getPaymentStatus());
    }

    @Test
    void rejectsRefundDoneWhenNotRefundNeeded() throws Exception {
        Long orderId = newOrder(boothId, 23);   // UNPAID — 환불 대상 아님

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/refund-done", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsDoubleRefundDone() throws Exception {
        Long orderId = newRefundNeededOrder(24);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/refund-done", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/refund-done", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertEquals("dashboard-admin", orderRepository.findById(orderId).orElseThrow().getRefundedBy());
    }

    @Test
    void rejectsRefundDoneForUnknownOrder() throws Exception {
        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/refund-done", 999999L)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void hidesOtherBoothOrderAsNotFoundOnRefund() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "국민은행 5678", null));
        Long otherOrderId = newOrder(otherBooth.getId(), 1);
        setPaymentStatus(otherOrderId, PaymentStatus.REFUND_NEEDED);
        setOrderStatus(otherOrderId, OrderStatus.CANCELED);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/refund-done", otherOrderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsRefundDoneWithoutAuthorization() throws Exception {
        Long orderId = newRefundNeededOrder(25);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/refund-done", orderId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
