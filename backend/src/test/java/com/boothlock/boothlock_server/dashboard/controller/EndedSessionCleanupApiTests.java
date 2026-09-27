package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.dashboard.domain.CallReason;
import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.order.OrderRaceTestFixture;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.KST;
import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.bearer;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 퇴실(O6) 뒤에 남는 것들(v0.6.13) — 종료된 세션의 취소 주문 되돌리기, 종료된 세션의 미확인 직원 호출.
 * 둘 다 "손님은 떠났는데 대시보드에서 계속 살아 있는" 상태를 만들었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EndedSessionCleanupApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired OrderRaceTestFixture fx;

    @BeforeEach
    void setUp() {
        fx.setUp();
    }

    @AfterEach
    void tearDown() {
        fx.cleanUp();
    }

    private void checkout() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", fx.table.getId())
                        .header("Authorization", bearer(fx.staffToken)))
                .andExpect(status().isOk());
    }

    private StaffCallEntity call(Long sessionId) {
        TableSessionEntity session = fx.tableSessionRepository.findById(sessionId).orElseThrow();
        return fx.staffCallRepository.save(new StaffCallEntity(session, CallReason.PAYMENT, LocalDateTime.now(KST)));
    }

    // ── 되돌리기 ─────────────────────────────────────────

    @Test
    void canceledOrderOfEndedSessionCannotBeRestored() throws Exception {
        Long sessionId = fx.activeSessionId();
        // 승인대기 주문 — 퇴실이 자동 거절(CANCELED)한다
        OrderCreateResponse pending = fx.customerOrder(fx.kimchiId, 1);
        checkout();
        assertEquals(OrderStatus.CANCELED, fx.reload(pending.orderId()).getStatus());

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", pending.orderId())
                        .header("Authorization", bearer(fx.staffToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.message").value(containsString("수기 주문")));

        assertEquals(OrderStatus.CANCELED, fx.reload(pending.orderId()).getStatus(), "떠난 손님 주문이 주방 대기열로 살아나면 안 된다");
        assertTrue(fx.tableSessionRepository.findById(sessionId).orElseThrow().getEndedAt() != null);
    }

    @Test
    void canceledOrderOfOpenSessionCanStillBeRestored() throws Exception {
        OrderCreateResponse order = fx.approvedCustomerOrder(fx.kimchiId, 1);
        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", order.orderId())
                        .header("Authorization", bearer(fx.staffToken))
                        .contentType("application/json").content("{\"reason\":\"실수\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", order.orderId())
                        .header("Authorization", bearer(fx.staffToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void autoCompletedOrderOfEndedSessionCanStillBeRestored() throws Exception {
        // O6가 접수 주문을 자동 완료(DONE)로 넘긴다 — 잘못 넘어간 것을 되돌리기로 살리는 것이 명세서 O6 5단계의 공식 복구 경로다
        OrderCreateResponse order = fx.approvedCustomerOrder(fx.kimchiId, 1);
        checkout();
        assertEquals(OrderStatus.DONE, fx.reload(order.orderId()).getStatus());

        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/restore", order.orderId())
                        .header("Authorization", bearer(fx.staffToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    // ── 직원 호출 ─────────────────────────────────────────

    @Test
    void checkoutAcksUnackedCallsAndDashboardHidesEndedSessionCalls() throws Exception {
        Long sessionId = fx.activeSessionId();
        StaffCallEntity pendingCall = call(sessionId);

        mockMvc.perform(get("/api/v1/admin/orders").header("Authorization", bearer(fx.staffToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls.length()").value(1));

        checkout();

        assertTrue(fx.staffCallRepository.findById(pendingCall.getId()).orElseThrow().isAcked(),
                "퇴실한 세션의 미확인 호출은 퇴실 트랜잭션에서 확인 처리된다");
        mockMvc.perform(get("/api/v1/admin/orders").header("Authorization", bearer(fx.staffToken)))
                .andExpect(jsonPath("$.calls.length()").value(0));
    }

    @Test
    void dashboardHidesUnackedCallOfSessionEndedWithoutCheckout() throws Exception {
        // 유휴 재스캔처럼 O6를 거치지 않고 끝난 세션 — acked=false로 남아도 목록에는 열린 세션 호출만 나온다
        Long endedSessionId = fx.activeSessionId();
        StaffCallEntity staleCall = call(endedSessionId);
        Long openSessionId = fx.openSession();   // 앞 세션을 닫고 새 세션을 연다
        call(openSessionId);

        mockMvc.perform(get("/api/v1/admin/orders").header("Authorization", bearer(fx.staffToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls.length()").value(1));
        assertFalse(fx.staffCallRepository.findById(staleCall.getId()).orElseThrow().isAcked());
    }
}
