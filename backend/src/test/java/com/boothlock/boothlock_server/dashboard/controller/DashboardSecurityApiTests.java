package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.dashboard.PosTestFixture;
import com.boothlock.boothlock_server.dashboard.domain.CallReason;
import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대시보드 운영자 API 전체의 부스 스코프·인증 매트릭스 (명세서 §1.2·§1.4·§7-4·§7-21).
 * 부스 A 토큰으로 부스 B의 주문·tableId·callId를 건드리면 전부 404(존재 은닉), 무토큰·위조 401, SUPER_ADMIN 403.
 * 그리고 어느 경우에도 B 부스 데이터가 바뀌지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardSecurityApiTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired PosTestFixture fx;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long bOrderId;
    private Long bItemId;
    private Long bCallId;
    private String forgedToken;

    @BeforeEach
    void setUp() {
        fx.setUp();
        bOrderId = fx.otherBoothManualOrder().orderId();
        // items는 LAZY — 트랜잭션 밖이라 EntityGraph로 함께 가져오는 조회를 쓴다
        bItemId = fx.orderRepository.findByIdAndBoothId(bOrderId, fx.otherBooth.getId()).orElseThrow()
                .getItems().get(0).getId();
        TableSessionEntity bSession = fx.tableSessionRepository
                .findByTableIdAndEndedAtIsNull(fx.otherBoothTable.getId()).orElseThrow();
        bCallId = fx.staffCallRepository.save(new StaffCallEntity(bSession, CallReason.HELP, LocalDateTime.now(KST))).getId();
        forgedToken = new BoothJwtProvider("attacker-controlled-secret-at-least-32-bytes").issue(fx.staff, Instant.now());
    }

    @AfterEach
    void tearDown() {
        fx.cleanUp();
    }

    /** 부스 A 운영자가 부스 B 자원을 겨누는 요청 전부 — 이름 → 요청 빌더 */
    private Map<String, Supplier<MockHttpServletRequestBuilder>> crossBoothRequests() {
        Map<String, Supplier<MockHttpServletRequestBuilder>> requests = new LinkedHashMap<>();
        requests.put("O10 tableId", () -> get("/api/v1/admin/orders").param("tableId", fx.otherBoothTable.getId().toString()));
        requests.put("O11 payment", () -> patch("/api/v1/admin/orders/{id}/payment", bOrderId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\"}"));
        requests.put("O12 complete", () -> patch("/api/v1/admin/orders/{id}/complete", bOrderId));
        requests.put("O13 cancel", () -> post("/api/v1/admin/orders/{id}/cancel", bOrderId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"테스트\"}"));
        requests.put("O14 tableId", () -> post("/api/v1/admin/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableId\":" + fx.otherBoothTable.getId() + ",\"items\":[{\"menuId\":" + fx.kimchiId + ",\"qty\":1}]}"));
        requests.put("O15 ack", () -> patch("/api/v1/admin/calls/{id}/ack", bCallId));
        requests.put("O21 refund-done", () -> post("/api/v1/admin/orders/{id}/refund-done", bOrderId));
        requests.put("O24 table-payment", () -> post("/api/v1/admin/orders/table-payment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableId\":" + fx.otherBoothTable.getId() + ",\"expectedTotal\":1000,\"method\":\"CASH\"}"));
        requests.put("item qty", () -> patch("/api/v1/admin/orders/{id}/items/{itemId}", bOrderId, bItemId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":1}"));
        requests.put("item cancel", () -> post("/api/v1/admin/orders/{id}/items/{itemId}/cancel", bOrderId, bItemId));
        return requests;
    }

    private void assertBoothBUntouched() {
        var order = fx.orderRepository.findById(bOrderId).orElseThrow();
        assertEquals(PaymentStatus.UNPAID, order.getPaymentStatus());
        assertEquals(com.boothlock.boothlock_server.global.domain.OrderStatus.RECEIVED, order.getStatus());
        assertNull(order.getApprovedBy());
        assertEquals(1000, order.getTotalAmount());
        assertFalse(fx.staffCallRepository.findById(bCallId).orElseThrow().isAcked());
        assertEquals(1, fx.orderRepository.count());   // B 테이블에 A 운영자의 수기 주문이 생기지 않았다
        assertEquals(TableStatus.OCCUPIED, fx.tableRepository.findById(fx.otherBoothTable.getId()).orElseThrow().getStatus());
        assertEquals(TableStatus.EMPTY, fx.tableRepository.findById(fx.table.getId()).orElseThrow().getStatus());
    }

    @Test
    void boothATokenCannotSeeOrTouchBoothBResources() throws Exception {
        // O21은 ADMIN 전용이라 ADMIN 토큰으로도 확인한다 — 권한이 있어도 남의 부스는 404
        for (String token : new String[] {fx.staffToken, fx.adminToken}) {
            for (var entry : crossBoothRequests().entrySet()) {
                if (entry.getKey().equals("O21 refund-done") && token.equals(fx.staffToken)) {
                    continue;   // STAFF는 403이 먼저 — 아래 별도 단언
                }
                mockMvc.perform(entry.getValue().get().header("Authorization", PosTestFixture.bearer(token)))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
            }
        }
        mockMvc.perform(crossBoothRequests().get("O21 refund-done").get().header("Authorization", PosTestFixture.bearer(fx.staffToken)))
                .andExpect(status().isForbidden());
        assertBoothBUntouched();
    }

    @Test
    void missingOrForgedTokenIsUnauthorizedEverywhere() throws Exception {
        for (var entry : crossBoothRequests().entrySet()) {
            mockMvc.perform(entry.getValue().get())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
            mockMvc.perform(entry.getValue().get().header("Authorization", PosTestFixture.bearer(forgedToken)))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(entry.getValue().get().header("Authorization", PosTestFixture.bearer("not-a-jwt")))
                    .andExpect(status().isUnauthorized());
        }
        assertBoothBUntouched();
    }

    @Test
    void superAdminIsForbiddenEverywhere() throws Exception {
        for (var entry : crossBoothRequests().entrySet()) {
            mockMvc.perform(entry.getValue().get().header("Authorization", PosTestFixture.bearer(fx.superAdminToken)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
        assertBoothBUntouched();
    }

    @Test
    void tokenWhoseBoothClaimMovedIsUnauthorized() throws Exception {
        // 계정이 B 부스로 옮겨진 뒤 옛 토큰(boothId=A)으로는 어느 API도 못 쓴다 — B 부스 자원이 열리면 안 된다
        jdbcTemplate.update("update staff_account set booth_id = ? where id = ?", fx.otherBooth.getId(), fx.staff.getId());
        for (var entry : crossBoothRequests().entrySet()) {
            mockMvc.perform(entry.getValue().get().header("Authorization", PosTestFixture.bearer(fx.staffToken)))
                    .andExpect(status().isUnauthorized());
        }
        assertBoothBUntouched();
    }
}
