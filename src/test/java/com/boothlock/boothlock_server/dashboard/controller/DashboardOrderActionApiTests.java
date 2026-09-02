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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O11 입금 확인·O12 완료 처리 API 테스트 (명세서 O11·O12) */
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
}
