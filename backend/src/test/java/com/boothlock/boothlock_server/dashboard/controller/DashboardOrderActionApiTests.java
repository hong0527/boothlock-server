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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O11 입금 확인·O12 완료 처리·O13 운영자 취소·O28 주문 승인 API 테스트 (명세서 O11·O12·O13·O28) */
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

    private void cancelAllItems(Long orderId) {
        jdbcTemplate.update("update order_item set canceled = true where order_id = ?", orderId);
    }

    private void hideOrder(Long orderId) {
        jdbcTemplate.update("update orders set hidden = true where id = ?", orderId);
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
    void confirmsPaymentOnDoneButUnpaidOrder() throws Exception {
        // O12 완료는 입금 전에도 가능(명세 O12)하므로 DONE·UNPAID가 생긴다 — 미수금이라 O11로 받아야 한다(UnpaidOrderRule).
        // 조건부 UPDATE의 조건은 payment_status='UNPAID'(+ 취소 아님)뿐이라 status는 보지 않는다
        Long orderId = newOrder(boothId, 21);
        setOrderStatus(orderId, OrderStatus.DONE);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/payment", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.approvedBy").value("dashboard-staff"));
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

    // ── O28 주문 승인(v0.6.10) ────────────────────────────────

    @Test
    void approvesPendingOrder() throws Exception {
        Long orderId = newOrder(boothId, 40);
        setOrderStatus(orderId, OrderStatus.PENDING_APPROVAL);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/approve", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        assertEquals(OrderStatus.RECEIVED, orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    @Test
    void rejectsApproveOnAlreadyReceivedOrder() throws Exception {
        Long orderId = newOrder(boothId, 41);   // RECEIVED 그대로(수기 주문처럼 승인대기를 거치지 않은 경우와 동치)

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/approve", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsApproveOnCanceledOrder() throws Exception {
        Long orderId = newOrder(boothId, 42);
        setOrderStatus(orderId, OrderStatus.CANCELED);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/approve", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsApproveForUnknownOrder() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/approve", 999999L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void hidesOtherBoothOrderAsNotFoundOnApprove() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "국민은행 5678", null));
        Long otherOrderId = newOrder(otherBooth.getId(), 1);
        setOrderStatus(otherOrderId, OrderStatus.PENDING_APPROVAL);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/approve", otherOrderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsApproveWithoutAuthorization() throws Exception {
        Long orderId = newOrder(boothId, 43);
        setOrderStatus(orderId, OrderStatus.PENDING_APPROVAL);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/approve", orderId))
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
    void staffCanRejectPendingApprovalOrder() throws Exception {
        // 거절(v0.6.10)은 별도 엔드포인트 없이 O13을 그대로 쓴다 — cancelByStaff가 이미 "CANCELED가 아닌 모든
        // 상태"를 대상으로 하므로 PENDING_APPROVAL도 그대로 취소된다(Figma "주문현황-승인대기" 641:1362의 거절 버튼)
        Long orderId = newOrder(boothId, 44);
        setOrderStatus(orderId, OrderStatus.PENDING_APPROVAL);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"주문 거절\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.cancelReason").value("주문 거절"));
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
    void defaultsReasonWhenBlank() throws Exception {
        // 프론트 디자인에 취소 사유 입력 UI가 없어서, 비어있으면 기본 사유로 채워 취소 자체는 진행된다
        Long orderId = newOrder(boothId, 18);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelReason").value("운영자 취소"));

        assertEquals(OrderStatus.CANCELED, orderRepository.findById(orderId).orElseThrow().getStatus());
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

    @Test
    void refundDoneWorksOnHiddenCanceledOrder() throws Exception {
        // 삭제(hidden=true)는 주문현황 목록에서만 숨기는 것 — 환불 처리(O21)는 계속 가능해야 한다
        Long orderId = newRefundNeededOrder(37);
        hideOrder(orderId);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/refund-done", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedBy").value("dashboard-admin"))
                .andExpect(jsonPath("$.refundedAt").exists());

        OrderEntity saved = orderRepository.findByIdAndBoothId(orderId, boothId).orElseThrow();   // 실제 삭제 아님
        assertEquals(PaymentStatus.REFUNDED, saved.getPaymentStatus());
        assertEquals(OrderStatus.CANCELED, saved.getStatus());                // 주문 상태는 그대로 CANCELED
        assertTrue(saved.isHidden());                                        // hidden도 그대로 유지
        assertEquals("dashboard-admin", saved.getRefundedBy());
        assertNotNull(saved.getRefundedAt());
        assertEquals(1, saved.getItems().size());                            // 주문 데이터(항목) 훼손 없음
    }

    // ── 취소복구 (주문현황 관리, 명세서 밖) ──────────────────

    @Test
    void restoresCanceledOrderToReceivedWithoutTouchingPaymentStatus() throws Exception {
        Long orderId = newOrder(boothId, 26);
        setPaymentStatus(orderId, PaymentStatus.REFUND_NEEDED);
        setOrderStatus(orderId, OrderStatus.CANCELED);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUND_NEEDED"))
                .andExpect(jsonPath("$.totalAmount").value(16000))
                .andExpect(jsonPath("$.items[0].menuName").value("김치전"))
                .andExpect(jsonPath("$.items[0].qty").value(2));

        OrderEntity saved = orderRepository.findById(orderId).orElseThrow();
        assertEquals(orderId, saved.getId());
        assertEquals(OrderStatus.RECEIVED, saved.getStatus());
        assertEquals(PaymentStatus.REFUND_NEEDED, saved.getPaymentStatus());   // 결제/환불 축은 그대로
    }

    @Test
    void restoresDoneOrderToReceivedWithoutTouchingPaymentStatus() throws Exception {
        // Figma 최종 디자인: 완료 탭도 취소 탭과 같은 "되돌리기" 버튼 하나 — DONE도 CANCELED와 같은 규칙으로 되돌아간다
        Long orderId = newOrder(boothId, 39);
        setPaymentStatus(orderId, PaymentStatus.PAID);
        setOrderStatus(orderId, OrderStatus.DONE);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));

        OrderEntity saved = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.RECEIVED, saved.getStatus());
        assertEquals(PaymentStatus.PAID, saved.getPaymentStatus());   // 결제 축은 그대로
    }

    @Test
    void restorePreservesTableLabelAndSessionId() throws Exception {
        OrderEntity order = new OrderEntity(
                boothId, 777L, "A3-30", LocalDate.of(2026, 9, 1),
                30, "idem-table-" + boothId + "-30", 16000, false,
                "A-3", LocalDateTime.of(2026, 9, 1, 18, 0));
        order.addItem(new OrderItemEntity(3L, "김치전", 8000, 2));
        Long orderId = orderRepository.save(order).getId();
        setOrderStatus(orderId, OrderStatus.CANCELED);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableLabel").value("A-3"))
                .andExpect(jsonPath("$.sessionId").value(777));

        OrderEntity saved = orderRepository.findById(orderId).orElseThrow();
        assertEquals("A-3", saved.getTableLabel());
        assertEquals(777L, saved.getSessionId());
    }

    @Test
    void rejectsRestoreWhenNotCanceled() throws Exception {
        Long orderId = newOrder(boothId, 27);   // RECEIVED 그대로

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsRestoreWhenAllItemsCanceled() throws Exception {
        // 항목이 전부 개별 취소(O6)돼 실질적으로 빈 주문이면 복구 대상이 없다
        Long orderId = newOrder(boothId, 28);
        setOrderStatus(orderId, OrderStatus.CANCELED);
        cancelAllItems(orderId);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertEquals(OrderStatus.CANCELED, orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    @Test
    void rejectsRestoreOfHiddenOrder() throws Exception {
        // 삭제(숨김) 처리된 주문은 다시 복구할 수 없다
        Long orderId = newOrder(boothId, 29);
        setOrderStatus(orderId, OrderStatus.CANCELED);
        hideOrder(orderId);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsRestoreForUnknownOrder() throws Exception {
        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", 999999L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void hidesOtherBoothOrderAsNotFoundOnRestore() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "국민은행 5678", null));
        Long otherOrderId = newOrder(otherBooth.getId(), 1);
        setOrderStatus(otherOrderId, OrderStatus.CANCELED);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", otherOrderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsRestoreWithoutAuthorization() throws Exception {
        Long orderId = newOrder(boothId, 31);
        setOrderStatus(orderId, OrderStatus.CANCELED);

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", orderId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ── 취소 주문 삭제 (주문현황 관리, 명세서 밖) ────────────

    @Test
    void deletesCanceledOrderWithoutRemovingRowOrTouchingPayment() throws Exception {
        Long orderId = newOrder(boothId, 32);
        setPaymentStatus(orderId, PaymentStatus.REFUND_NEEDED);
        setOrderStatus(orderId, OrderStatus.CANCELED);

        mockMvc.perform(delete("/api/v1/admin/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        OrderEntity saved = orderRepository.findByIdAndBoothId(orderId, boothId).orElseThrow();   // row가 그대로 있어야 한다
        assertTrue(saved.isHidden());
        assertEquals(OrderStatus.CANCELED, saved.getStatus());
        assertEquals(PaymentStatus.REFUND_NEEDED, saved.getPaymentStatus());   // 결제/환불 축 불변
        assertEquals(1, saved.getItems().size());                             // 항목도 그대로 보존
    }

    @Test
    void deletedOrderDisappearsFromDashboardListButStaysInRepository() throws Exception {
        Long orderId = newOrder(boothId, 33);
        setOrderStatus(orderId, OrderStatus.CANCELED);
        hideOrder(orderId);

        mockMvc.perform(get("/api/v1/admin/orders")
                        .header("Authorization", "Bearer " + token)
                        .param("status", "CANCELED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(0));

        assertNotNull(orderRepository.findById(orderId).orElseThrow());
    }

    @Test
    void rejectsDeleteWhenNotCanceled() throws Exception {
        Long orderId = newOrder(boothId, 34);   // RECEIVED 그대로

        mockMvc.perform(delete("/api/v1/admin/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsDoubleDelete() throws Exception {
        Long orderId = newOrder(boothId, 35);
        setOrderStatus(orderId, OrderStatus.CANCELED);

        mockMvc.perform(delete("/api/v1/admin/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/admin/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsDeleteForUnknownOrder() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/orders/{orderId}", 999999L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void hidesOtherBoothOrderAsNotFoundOnDelete() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "국민은행 5678", null));
        Long otherOrderId = newOrder(otherBooth.getId(), 1);
        setOrderStatus(otherOrderId, OrderStatus.CANCELED);

        mockMvc.perform(delete("/api/v1/admin/orders/{orderId}", otherOrderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDeleteWithoutAuthorization() throws Exception {
        Long orderId = newOrder(boothId, 36);
        setOrderStatus(orderId, OrderStatus.CANCELED);

        mockMvc.perform(delete("/api/v1/admin/orders/{orderId}", orderId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void serializesConcurrentRestoreAndDeleteForSameOrder() throws Exception {
        // 같은 주문에 취소복구(POST .../restore)와 삭제(DELETE .../{id})가 동시에 들어와도 행 잠금(취소복구는
        // FOR UPDATE 조회, 삭제는 조건부 UPDATE)으로 직렬화되어 정확히 하나만 성공해야 한다.
        // serializesConcurrentPaymentConfirmationsForSameOrder(O11)와 같은 이유·같은 패턴의 검증이다.
        Long orderId = newOrder(boothId, 38);
        setOrderStatus(orderId, OrderStatus.CANCELED);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Integer> restoreAttempt = () -> {
            ready.countDown();
            start.await();
            return mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", orderId)
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus();
        };
        Callable<Integer> deleteAttempt = () -> {
            ready.countDown();
            start.await();
            return mockMvc.perform(delete("/api/v1/admin/orders/{orderId}", orderId)
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus();
        };

        List<Future<Integer>> futures = List.of(executor.submit(restoreAttempt), executor.submit(deleteAttempt));
        ready.await();
        start.countDown();
        List<Integer> statuses = futures.stream().map(f -> {
            try {
                return f.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
        executor.shutdown();

        int restoreStatus = statuses.get(0);   // futures 순서 그대로 — restoreAttempt 결과
        int deleteStatus = statuses.get(1);    // deleteAttempt 결과

        long successCount = statuses.stream().filter(s -> s == 200 || s == 204).count();
        long conflictCount = statuses.stream().filter(s -> s == 409).count();
        assertEquals(1, successCount, "정확히 하나만 성공해야 함: " + statuses);
        assertEquals(1, conflictCount, "나머지 하나는 409(INVALID_STATE)여야 함: " + statuses);

        OrderEntity finalState = orderRepository.findByIdAndBoothId(orderId, boothId).orElseThrow();
        assertEquals(1, finalState.getItems().size());   // 어느 쪽이 이기든 주문 데이터(항목)는 훼손되지 않는다

        // 복구가 이겼으면 RECEIVED+hidden=false, 삭제가 이겼으면 CANCELED+hidden=true — 그 외 조합은 없어야 한다
        if (restoreStatus == 200 && deleteStatus == 409) {
            assertEquals(OrderStatus.RECEIVED, finalState.getStatus());
            assertTrue(!finalState.isHidden());
        } else if (deleteStatus == 204 && restoreStatus == 409) {
            assertEquals(OrderStatus.CANCELED, finalState.getStatus());
            assertTrue(finalState.isHidden());
        } else {
            throw new AssertionError("예상 밖 상태 조합 — restoreStatus=" + restoreStatus + ", deleteStatus=" + deleteStatus
                    + ", final=" + finalState.getStatus() + "/" + finalState.isHidden());
        }
    }
}
