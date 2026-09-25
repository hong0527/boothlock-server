package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.dashboard.PosTestFixture;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;

import org.hamcrest.Matchers;
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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O24 테이블 일괄 입금 확인 API — 활성 세션 미결제만·expectedTotal 대조·O11과 같은 승인 기록 (팀 확정 규칙) */
@SpringBootTest
@AutoConfigureMockMvc
class TablePaymentApiTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired PosTestFixture fx;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OrderNumberingService numberingService;

    @BeforeEach
    void setUp() {
        fx.setUp();
    }

    @AfterEach
    void tearDown() {
        fx.cleanUp();
    }

    private MockHttpServletRequestBuilder pay(String token, Long tableId, int expectedTotal, String method) {
        return post("/api/v1/admin/orders/table-payment")
                .header("Authorization", PosTestFixture.bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableId\":" + tableId + ",\"expectedTotal\":" + expectedTotal + ",\"method\":\"" + method + "\"}");
    }

    private OrderEntity order(Long id) {
        return fx.orderRepository.findById(id).orElseThrow();
    }

    @Test
    void confirmsOnlyUnpaidOrdersOfActiveSessionsAndRecordsApprover() throws Exception {
        // 지난 손님(종료된 세션)의 미결제 주문 — 대상 아님
        Long oldSessionOrder = fx.manualOrder(fx.table, fx.colaId, 1).orderId();
        TableSessionEntity old = fx.activeSession();
        old.end(LocalDateTime.now(KST));
        fx.tableSessionRepository.save(old);

        Long manual = fx.manualOrder(fx.table, fx.kimchiId, 2).orderId();      // 16000, 새 세션
        Long customer = fx.approvedCustomerOrder(fx.colaId, 1).orderId();      // 5000, 같은 세션
        Long alreadyPaid = fx.customerOrder(fx.kimchiId, 1).orderId();
        jdbcTemplate.update("update orders set payment_status = 'PAID', payment_method = 'CASH', approved_by = 'earlier' where id = ?", alreadyPaid);
        Long canceled = fx.customerOrder(fx.kimchiId, 3).orderId();
        jdbcTemplate.update("update orders set status = 'CANCELED' where id = ?", canceled);
        Long done = fx.customerOrder(fx.colaId, 2).orderId();                 // 10000, 완료 처리됐지만 미입금 — 미수금이라 대상(UnpaidOrderRule)
        jdbcTemplate.update("update orders set status = 'DONE' where id = ?", done);
        TableEntity otherTable = fx.tableRepository.save(new TableEntity(fx.booth, "B-1", "pos-token-b1"));
        Long otherTableOrder = fx.manualOrder(otherTable, fx.kimchiId, 1).orderId();

        mockMvc.perform(pay(fx.staffToken, fx.table.getId(), 31000, "BANK_TRANSFER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(31000))
                .andExpect(jsonPath("$.orders.length()").value(3))
                .andExpect(jsonPath("$.orders[*].orderId", Matchers.contains(manual.intValue(), customer.intValue(), done.intValue())))
                .andExpect(jsonPath("$.orders[*].paymentStatus", Matchers.everyItem(Matchers.is("PAID"))))
                .andExpect(jsonPath("$.orders[*].paymentMethod", Matchers.everyItem(Matchers.is("BANK_TRANSFER"))))
                .andExpect(jsonPath("$.orders[*].approvedBy", Matchers.everyItem(Matchers.is("pos-staff"))))
                .andExpect(jsonPath("$.orders[0].approvedAt").exists())
                .andExpect(jsonPath("$.orders[2].status").value("DONE"))     // 주문 축은 건드리지 않는다
                .andExpect(jsonPath("$.orders[*].sessionId", Matchers.everyItem(Matchers.is(fx.activeSession().getId().intValue()))));

        assertEquals(PaymentStatus.PAID, order(manual).getPaymentStatus());
        assertEquals(PaymentStatus.PAID, order(customer).getPaymentStatus());
        assertEquals(PaymentStatus.PAID, order(done).getPaymentStatus());
        assertEquals(PaymentStatus.UNPAID, order(oldSessionOrder).getPaymentStatus());
        assertEquals("earlier", order(alreadyPaid).getApprovedBy());
        assertEquals(PaymentStatus.UNPAID, order(canceled).getPaymentStatus());
        assertEquals(PaymentStatus.UNPAID, order(otherTableOrder).getPaymentStatus());

        // 매출 집계(O18)에 그대로 잡힌다 (earlier CASH 1건 + 이번 이체 31000)
        String businessDate = numberingService.businessDateOf(order(manual).getCreatedAt()).toString();
        mockMvc.perform(get("/api/v1/admin/stats/sales").param("date", businessDate)
                        .header("Authorization", PosTestFixture.bearer(fx.adminToken)))
                .andExpect(jsonPath("$.byMethod.BANK_TRANSFER").value(31000));

        // 두 번째 클릭 — 이제 대상이 없다
        mockMvc.perform(pay(fx.staffToken, fx.table.getId(), 31000, "BANK_TRANSFER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsWhenExpectedTotalDiffersAndChangesNothing() throws Exception {
        Long first = fx.manualOrder(fx.table, fx.kimchiId, 2).orderId();   // 16000
        Long late = fx.approvedCustomerOrder(fx.colaId, 1).orderId();      // 화면 갱신 전에 들어와 승인까지 끝난 5000

        mockMvc.perform(pay(fx.staffToken, fx.table.getId(), 16000, "CASH"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.message", Matchers.containsString("21000")));

        assertEquals(PaymentStatus.UNPAID, order(first).getPaymentStatus());
        assertNull(order(first).getApprovedBy());
        assertEquals(PaymentStatus.UNPAID, order(late).getPaymentStatus());
    }

    @Test
    void emptyTableIsConflictNotSuccess() throws Exception {
        mockMvc.perform(pay(fx.staffToken, fx.table.getId(), 0, "CASH"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void otherBoothOrUnknownTableIsNotFound() throws Exception {
        Long mine = fx.manualOrder(fx.table, fx.kimchiId, 1).orderId();

        mockMvc.perform(pay(fx.otherBoothToken, fx.table.getId(), 8000, "CASH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(pay(fx.staffToken, fx.otherBoothTable.getId(), 0, "CASH"))
                .andExpect(status().isNotFound());
        mockMvc.perform(pay(fx.staffToken, 999999L, 0, "CASH"))
                .andExpect(status().isNotFound());

        assertEquals(PaymentStatus.UNPAID, order(mine).getPaymentStatus());
    }

    @Test
    void requiresBoothStaffAndValidBody() throws Exception {
        Long mine = fx.manualOrder(fx.table, fx.kimchiId, 1).orderId();

        mockMvc.perform(post("/api/v1/admin/orders/table-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":" + fx.table.getId() + ",\"expectedTotal\":8000,\"method\":\"CASH\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(pay(fx.superAdminToken, fx.table.getId(), 8000, "CASH"))
                .andExpect(status().isForbidden());

        for (String body : List.of(
                "{\"expectedTotal\":8000,\"method\":\"CASH\"}",
                "{\"tableId\":" + fx.table.getId() + ",\"method\":\"CASH\"}",
                "{\"tableId\":" + fx.table.getId() + ",\"expectedTotal\":8000}",
                "{\"tableId\":" + fx.table.getId() + ",\"expectedTotal\":-1,\"method\":\"CASH\"}",
                "{\"tableId\":" + fx.table.getId() + ",\"expectedTotal\":8000,\"method\":\"CARD\"}")) {
            mockMvc.perform(post("/api/v1/admin/orders/table-payment")
                            .header("Authorization", PosTestFixture.bearer(fx.staffToken))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertEquals(PaymentStatus.UNPAID, order(mine).getPaymentStatus());

        mockMvc.perform(pay(fx.adminToken, fx.table.getId(), 8000, "CASH"))
                .andExpect(status().isOk());
        assertEquals(PaymentMethod.CASH, order(mine).getPaymentMethod());
    }
}
