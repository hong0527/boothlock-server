package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.dashboard.domain.CallReason;
import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.dto.AuthenticatedSession;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.service.TableSessionAuthService;

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
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O25 자리 이동·O26 자리 합석(명세서 밖 파일럿, 2026-09-30 현장 피드백) — QR(table_token)을 그대로 둔 채
 * 세션만 다른 물리 테이블로 옮기거나(이동), 두 세션의 주문을 하나로 모으는(합석) 기능.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TableMoveAndMergeApiTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired StaffCallRepository staffCallRepository;
    @Autowired TableSessionAuthService tableSessionAuthService;
    @Autowired JdbcTemplate jdbcTemplate;

    private BoothEntity booth;
    private TableEntity tableA;
    private TableEntity tableB;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        booth = boothRepository.save(new BoothEntity("이동합석부스", "은행 1234", null));
        tableA = tableRepository.save(new TableEntity(booth, "A-1", "token-a"));
        tableB = tableRepository.save(new TableEntity(booth, "A-2", "token-b"));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staffRepository.save(new StaffAccountEntity(booth, "admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        adminToken = login("admin");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        staffCallRepository.deleteAll();
        orderRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffRepository.deleteAll();
        boothRepository.deleteAll();
    }

    private TableSessionEntity openSession(TableEntity table, String token) {
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, token, LocalDateTime.now(KST)));
        table.occupy();
        tableRepository.save(table);
        return session;
    }

    private int nextSeq = 1;

    private OrderEntity newOrder(TableSessionEntity session, String orderNo, OrderStatus status, PaymentStatus paymentStatus) {
        OrderEntity order = new OrderEntity(booth.getId(), session.getId(), orderNo, LocalDate.now(KST), nextSeq++,
                "idem-" + orderNo, 8000, false, session.getTable().getLabel(), LocalDateTime.now(KST));
        order.addItem(new OrderItemEntity(1L, "떡볶이", 8000, 1));
        OrderEntity saved = orderRepository.saveAndFlush(order);
        if (status != OrderStatus.RECEIVED || paymentStatus != PaymentStatus.UNPAID) {
            jdbcTemplate.update("update orders set status = ?, payment_status = ? where id = ?",
                    status.name(), paymentStatus.name(), saved.getId());
        }
        return saved;
    }

    // ===================== 자리 이동 =====================

    @Test
    void movesSessionToEmptyTargetAndKeepsSessionTokenValid() throws Exception {
        TableSessionEntity session = openSession(tableA, "session-a");

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/move", tableA.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTableId\":" + tableB.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0].id").value(tableA.getId()))
                .andExpect(jsonPath("$.tables[0].status").value("EMPTY"))
                .andExpect(jsonPath("$.tables[1].id").value(tableB.getId()))
                .andExpect(jsonPath("$.tables[1].status").value("OCCUPIED"));

        TableSessionEntity reloaded = tableSessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(tableB.getId(), reloaded.getTable().getId());

        // 손님 세션 토큰은 그대로 유효하고, 다음 인증부터 새 테이블 라벨을 돌려준다 — 재스캔 불필요
        AuthenticatedSession auth = tableSessionAuthService.authenticate("session-a");
        assertEquals("A-2", auth.tableLabel());
    }

    @Test
    void rejectsMoveWhenTargetAlreadyOccupied() throws Exception {
        openSession(tableA, "session-a");
        openSession(tableB, "session-b");

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/move", tableA.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTableId\":" + tableB.getId() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsMoveWhenSourceIsEmpty() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/move", tableA.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTableId\":" + tableB.getId() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsMoveToSameTable() throws Exception {
        openSession(tableA, "session-a");

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/move", tableA.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTableId\":" + tableA.getId() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsMoveInvolvingOtherBoothsTable() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("남의 부스", "은행 9999", null));
        TableEntity otherTable = tableRepository.save(new TableEntity(otherBooth, "B-1", "token-other"));
        openSession(tableA, "session-a");

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/move", tableA.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTableId\":" + otherTable.getId() + "}"))
                .andExpect(status().isNotFound());
    }

    // ===================== 자리 합석 =====================

    @Test
    void mergesOrdersIntoTargetAndEndsSourceSession() throws Exception {
        TableSessionEntity sourceSession = openSession(tableA, "session-a");
        TableSessionEntity targetSession = openSession(tableB, "session-b");
        OrderEntity paidOrder = newOrder(sourceSession, "A1-1", OrderStatus.DONE, PaymentStatus.PAID);
        OrderEntity unpaidOrder = newOrder(sourceSession, "A1-2", OrderStatus.RECEIVED, PaymentStatus.UNPAID);
        staffCallRepository.save(new StaffCallEntity(sourceSession, CallReason.HELP, LocalDateTime.now(KST)));

        mockMvc.perform(post("/api/v1/admin/tables/merge")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceTableId\":" + tableA.getId() + ",\"targetTableId\":" + tableB.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0].status").value("EMPTY"))
                .andExpect(jsonPath("$.tables[1].status").value("OCCUPIED"));

        // 주문은 target 세션 밑으로 옮겨지고, 상태는 그대로다(결제·환불 축 불변)
        OrderEntity reloadedPaid = orderRepository.findById(paidOrder.getId()).orElseThrow();
        OrderEntity reloadedUnpaid = orderRepository.findById(unpaidOrder.getId()).orElseThrow();
        assertEquals(targetSession.getId(), reloadedPaid.getSessionId());
        assertEquals(targetSession.getId(), reloadedUnpaid.getSessionId());
        assertEquals(OrderStatus.DONE, reloadedPaid.getStatus());
        assertEquals(PaymentStatus.PAID, reloadedPaid.getPaymentStatus());
        assertEquals(OrderStatus.RECEIVED, reloadedUnpaid.getStatus());
        assertEquals(PaymentStatus.UNPAID, reloadedUnpaid.getPaymentStatus());

        // source 세션은 종료 — 그 토큰으로는 이제 410
        assertThrows(com.boothlock.boothlock_server.global.error.SessionExpiredException.class,
                () -> tableSessionAuthService.authenticate("session-a"));

        // source에 남아 있던 미확인 호출은 확인 처리됨(O6 퇴실과 같은 방식)
        assertTrue(staffCallRepository.findUnackedByBoothId(booth.getId()).isEmpty());
    }

    @Test
    void rejectsMergeWhenEitherTableIsEmpty() throws Exception {
        openSession(tableA, "session-a");

        mockMvc.perform(post("/api/v1/admin/tables/merge")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceTableId\":" + tableA.getId() + ",\"targetTableId\":" + tableB.getId() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsMergeOfSameTable() throws Exception {
        openSession(tableA, "session-a");

        mockMvc.perform(post("/api/v1/admin/tables/merge")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceTableId\":" + tableA.getId() + ",\"targetTableId\":" + tableA.getId() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsMoveAndMergeWithoutAuthorization() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/move", tableA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTableId\":" + tableB.getId() + "}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/tables/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceTableId\":" + tableA.getId() + ",\"targetTableId\":" + tableB.getId() + "}"))
                .andExpect(status().isUnauthorized());
    }

    private String login(String loginId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Credentials(loginId, "password"))))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("accessToken").asText();
        assertNotNull(token);
        return token;
    }

    private record Credentials(String loginId, String password) {
    }
}
