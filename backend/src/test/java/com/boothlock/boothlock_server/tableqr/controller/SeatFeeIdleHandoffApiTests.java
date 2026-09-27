package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.order.OrderRaceTestFixture;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.KST;
import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.bearer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자릿세 유휴 인계 이어받기(v0.6.13) — HTTP 경계(C1·인원수·C3·O6)에서 끝까지 확인한다.
 *
 * <p>재현된 결함: 자릿세까지 결제를 끝낸 일행이 폰을 안 만진 채 유휴 임계를 넘기고 QR을 다시 찍으면 C1이 세션을 바꿨고,
 * 새 세션은 인원을 다시 묻고 첫 주문에 자릿세를 한 번 더 붙였다(같은 일행 이중 청구).
 * 유휴는 시계를 돌리는 대신 세션 시각을 임계(테스트 기본 180분)보다 과거로 옮겨 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SeatFeeIdleHandoffApiTests {

    private static final int MENU_PRICE = 8000;   // 김치전
    private static final int FEE = 3000;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OrderRaceTestFixture fx;

    @BeforeEach
    void setUp() {
        fx.setUp();
    }

    @AfterEach
    void tearDown() {
        fx.cleanUp();
    }

    private JsonNode scan() throws Exception {
        String body = mockMvc.perform(post("/api/v1/table-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"race-token-a3\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void choosePartySize(String sessionToken, int partySize) throws Exception {
        mockMvc.perform(patch("/api/v1/table-sessions/party-size")
                        .header("X-Session-Token", sessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partySize\":" + partySize + "}"))
                .andExpect(status().isOk());
    }

    private ResultActions order(String sessionToken) throws Exception {
        return mockMvc.perform(post("/api/v1/orders")
                .header("X-Session-Token", sessionToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"menuId\":" + fx.kimchiId + ",\"qty\":1}]}"));
    }

    private Long orderId(ResultActions created) throws Exception {
        return objectMapper.readTree(created.andReturn().getResponse().getContentAsString()).get("orderId").asLong();
    }

    /** 승인(O28)하고 입금 확인(O11)까지 — 미결제가 남으면 유휴여도 활성이라 인계가 일어나지 않는다 */
    private void approveAndPay(Long orderId) {
        fx.orderRepository.approve(orderId, fx.booth.getId());
        assertEquals(1, fx.orderRepository.markPaid(orderId, fx.booth.getId(), PaymentMethod.BANK_TRANSFER,
                "race-staff", LocalDateTime.now(KST)));
    }

    private Long sessionIdOf(String sessionToken) {
        return fx.tableSessionRepository.findBySessionToken(sessionToken).orElseThrow().getId();
    }

    /** 세션을 유휴 임계(180분)보다 오래 방치한 것으로 만든다 */
    private void makeIdle(String sessionToken) {
        LocalDateTime longAgo = LocalDateTime.now(KST).minusMinutes(200);
        jdbcTemplate.update("update table_session set started_at = ?, last_activity_at = ? where id = ?",
                longAgo, longAgo, sessionIdOf(sessionToken));
    }

    /** 첫 일행: 인원 3명으로 자릿세 포함 첫 주문 — 주문 id를 돌려준다 */
    private Long firstPartyOrdersWithSeatFee(String sessionToken) throws Exception {
        choosePartySize(sessionToken, 3);
        ResultActions created = order(sessionToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(MENU_PRICE + FEE * 3));
        return orderId(created);
    }

    @Test
    void idleHandoffInheritsPaidSeatFeeAndPartySize() throws Exception {
        String first = scan().get("sessionToken").asString();
        approveAndPay(firstPartyOrdersWithSeatFee(first));
        makeIdle(first);

        JsonNode rescan = scan();
        String second = rescan.get("sessionToken").asString();
        assertNotEquals(first, second, "유휴 세션은 복원되지 않고 새 세션이 열린다(앞 토큰을 넘기지 않는다)");
        assertFalse(rescan.get("restored").asBoolean());
        assertEquals(3, rescan.get("partySize").asInt(), "앞 세션 인원수를 이어받아야 프론트가 인원 선택을 다시 띄우지 않는다");

        TableSessionEntity prev = fx.tableSessionRepository.findBySessionToken(first).orElseThrow();
        TableSessionEntity next = fx.tableSessionRepository.findBySessionToken(second).orElseThrow();
        assertEquals(prev.getEndedAt(), next.getStartedAt(), "유휴 인계는 옛 ended_at == 새 started_at");

        // 인원수를 다시 보내지 않아도(C1이 준 값 그대로) 409 없이 받고, 자릿세는 다시 붙지 않는다
        order(second)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(MENU_PRICE))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void c1ReportsSeatFeeChargedOnlyWhenInheritedOrPaid() throws Exception {
        // 주문 확인 화면의 자릿세 미리보기 근거 — C4는 새 세션 주문만 돌려줘 이어받은 자릿세를 프론트가 볼 수 없다
        JsonNode fresh = scan();
        assertFalse(fresh.get("seatFeeCharged").asBoolean(), "새 세션은 아직 자릿세를 내지 않았다");
        String first = fresh.get("sessionToken").asString();
        approveAndPay(firstPartyOrdersWithSeatFee(first));
        makeIdle(first);

        JsonNode rescan = scan();
        assertNotEquals(first, rescan.get("sessionToken").asString());
        assertTrue(rescan.get("seatFeeCharged").asBoolean(), "유휴 인계로 이어받은 자릿세는 C1이 알려 준다");
    }

    @Test
    void manualOrderAfterIdleHandoffStartsNewParty() throws Exception {
        // O14 수기 주문이 유휴 세션을 끝내고 연 자리는 새 일행이다 — 인원수·자릿세를 이어받지 않는다
        String first = scan().get("sessionToken").asString();
        approveAndPay(firstPartyOrdersWithSeatFee(first));
        makeIdle(first);

        fx.manualOrder(fx.kimchiId, 1);

        JsonNode rescan = scan();
        String second = rescan.get("sessionToken").asString();
        assertNotEquals(first, second);
        assertTrue(rescan.get("restored").asBoolean(), "수기 주문이 연 세션을 복원한다");
        assertTrue(!rescan.hasNonNull("partySize"), "운영자가 연 세션에는 앞 일행 인원수를 옮기지 않는다");
        assertFalse(rescan.get("seatFeeCharged").asBoolean());
        TableSessionEntity prev = fx.tableSessionRepository.findBySessionToken(first).orElseThrow();
        TableSessionEntity next = fx.tableSessionRepository.findBySessionToken(second).orElseThrow();
        assertNotEquals(prev.getEndedAt(), next.getStartedAt(), "유휴 인계 표지(ended_at == started_at)를 만들지 않는다");

        order(second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PARTY_SIZE_REQUIRED"));
        choosePartySize(second, 2);
        order(second)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(MENU_PRICE + FEE * 2));
    }

    @Test
    void idleHandoffWithoutPartySizeCopyIsNotAskedAgain() throws Exception {
        // 인원수 이어받기와 별개로, 인원수가 없는 세션도 앞 세션 자릿세를 보면 PARTY_SIZE_REQUIRED를 내지 않는다
        String first = scan().get("sessionToken").asString();
        approveAndPay(firstPartyOrdersWithSeatFee(first));
        makeIdle(first);
        String second = scan().get("sessionToken").asString();
        jdbcTemplate.update("update table_session set party_size = null where id = ?", sessionIdOf(second));

        order(second)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(MENU_PRICE));
    }

    @Test
    void newPartyAfterCheckoutIsChargedAgain() throws Exception {
        String first = scan().get("sessionToken").asString();
        approveAndPay(firstPartyOrdersWithSeatFee(first));

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", fx.table.getId())
                        .header("Authorization", bearer(fx.staffToken)))
                .andExpect(status().isOk());

        JsonNode rescan = scan();
        String second = rescan.get("sessionToken").asString();
        assertTrue(!rescan.hasNonNull("partySize"), "퇴실 뒤 새 손님은 인원을 새로 고른다");
        TableSessionEntity prev = fx.tableSessionRepository.findBySessionToken(first).orElseThrow();
        TableSessionEntity next = fx.tableSessionRepository.findBySessionToken(second).orElseThrow();
        assertNotEquals(prev.getEndedAt(), next.getStartedAt(), "O6 종료 시각은 뒤 스캔의 시작 시각과 같을 수 없다");

        order(second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PARTY_SIZE_REQUIRED"));
        choosePartySize(second, 2);
        order(second)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(MENU_PRICE + FEE * 2));
    }

    @Test
    void canceledPredecessorSeatFeeIsNotInherited() throws Exception {
        String first = scan().get("sessionToken").asString();
        Long feeOrderId = firstPartyOrdersWithSeatFee(first);
        assertEquals(1, fx.orderRepository.cancelByStaff(feeOrderId, fx.booth.getId(), "주문 거절", "race-staff",
                LocalDateTime.now(KST)));
        makeIdle(first);

        JsonNode rescan = scan();
        String second = rescan.get("sessionToken").asString();
        assertTrue(!rescan.hasNonNull("partySize"), "앞 세션이 실제로 낸 자릿세가 없으면 인원을 옮기지 않는다");

        order(second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PARTY_SIZE_REQUIRED"));
        choosePartySize(second, 2);
        order(second)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(MENU_PRICE + FEE * 2));
    }

    @Test
    void predecessorSeatFeeFromAnotherBusinessDateIsNotInherited() throws Exception {
        String first = scan().get("sessionToken").asString();
        Long feeOrderId = firstPartyOrdersWithSeatFee(first);
        approveAndPay(feeOrderId);
        // 어제 영업일에 낸 자릿세 — 다음 날 같은 자리는 새로 받는다
        java.time.LocalDate charged = jdbcTemplate.queryForObject(
                "select business_date from orders where id = ?", java.time.LocalDate.class, feeOrderId);
        jdbcTemplate.update("update orders set business_date = ? where id = ?", charged.minusDays(1), feeOrderId);
        makeIdle(first);

        JsonNode rescan = scan();
        String second = rescan.get("sessionToken").asString();
        assertTrue(!rescan.hasNonNull("partySize"));
        choosePartySize(second, 4);
        order(second)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(MENU_PRICE + FEE * 4));
    }

    @Test
    void inheritanceWalksBackThroughConsecutiveIdleHandoffs() throws Exception {
        // 자릿세를 낸 세션 → (주문 없이) 유휴 인계 → 다시 유휴 인계. 두 단계 앞의 자릿세도 이어받는다
        String first = scan().get("sessionToken").asString();
        approveAndPay(firstPartyOrdersWithSeatFee(first));
        makeIdle(first);
        String second = scan().get("sessionToken").asString();
        makeIdle(second);
        // makeIdle이 second의 started_at을 옮겨 first와의 연결(ended_at == started_at)이 끊기므로 되돌려 둔다
        jdbcTemplate.update("update table_session set started_at = (select ended_at from table_session where id = ?) where id = ?",
                sessionIdOf(first), sessionIdOf(second));

        JsonNode third = scan();
        assertEquals(3, third.get("partySize").asInt());
        order(third.get("sessionToken").asString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(MENU_PRICE));

        List<Long> orderSessions = fx.orderRepository.findAll().stream().map(o -> o.getSessionId()).toList();
        assertEquals(2, orderSessions.size());
    }
}
