package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.global.seat.SeatIdlePolicy;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableStatus;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionCreateRequest;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionResponse;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.service.TableSessionService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 좌석 활성 정의가 C1 세션 복원·O3 좌석 현황·E1 좌석 집계 세 곳에서 같은지 (명세서 §1.2·§7-9, SeatIdlePolicy).
 *
 * <p>재현된 결함: 마지막 활동 5시간 전 세션을 E1은 빈자리로 세는데, 그걸 보고 앉은 새 손님이 QR을 찍으면
 * C1이 restored:true로 앞 손님 토큰을 돌려줘 앞 손님 주문을 보고 미결제를 취소할 수 있었다.
 *
 * <p>시계는 고정·조정 가능한 시계를 유휴 정책에만 넣는다(전역 Clock 빈 없음). 테이블 파트의 세션 시각도 같은 정책 시계로 기록된다.
 */
@SpringBootTest(properties = "boothlock.event.booths-cache-seconds=0")
// 홈 화면 목록(E1)의 10초 캐시를 끈다 — 퇴실·유휴 판정 직후 E1 결과를 바로 비교하므로 캐시가 켜져 있으면 앞 결과가 보인다
@AutoConfigureMockMvc
class SeatIdleConsistencyTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final MutableClock CLOCK = new MutableClock();

    /** 정책 시계를 옮길 수 있게 한다 — 영업일 경계를 넘기는 테스트용 */
    static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.now();

        void setKst(LocalDateTime at) {
            instant = at.atZone(KST).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;   // 일부러 UTC — 정책은 시계 시간대와 무관하게 KST로 옮겨 써야 한다
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class MovableClockConfig {
        @Bean
        @Primary
        SeatIdlePolicy movableSeatIdlePolicy(OrderNumberingService orderNumberingService) {
            return new SeatIdlePolicy(180, CLOCK, orderNumberingService);
        }
    }

    /** 영업일 2026-09-15의 저녁 */
    private static final LocalDateTime EVENING = LocalDateTime.of(2026, 9, 15, 20, 0);
    private static final LocalDate DAY_ONE = LocalDate.of(2026, 9, 15);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired DailyCounterRepository dailyCounterRepository;
    @Autowired TableSessionService tableSessionService;

    private BoothEntity booth;
    private int orderSeq;

    @BeforeEach
    void setUp() {
        cleanUp();
        CLOCK.setKst(EVENING);
        booth = boothRepository.save(new BoothEntity("판정 부스", "은행 1234", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staffRepository.save(new StaffAccountEntity(booth, "admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        orderSeq = 0;
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        orderRepository.deleteAll();
        dailyCounterRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffRepository.deleteAll();
        boothRepository.deleteAll();
    }

    // ── 시딩 ─────────────────────────────────────────────

    private TableEntity occupiedTable(String label) {
        TableEntity table = new TableEntity(booth, label, "tok-idle-" + label);
        table.occupy();
        return tableRepository.save(table);
    }

    private TableSessionEntity sessionLastActiveAt(TableEntity table, String token, LocalDateTime lastActivityAt) {
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, token, lastActivityAt.minusMinutes(30)));
        tableSessionRepository.touchIfActive(session.getId(), lastActivityAt);
        return session;
    }

    private OrderEntity unpaidOrder(Long sessionId, LocalDate businessDate) {
        orderSeq++;
        return orderRepository.save(new OrderEntity(booth.getId(), sessionId, "I" + orderSeq, businessDate,
                orderSeq, "idem-idle-" + orderSeq, 5000, false, businessDate.atTime(19, 0)));
    }

    /** 완료 처리(O12)됐지만 입금이 안 된 주문 — 미수금이라 미결제 예외에 그대로 잡혀야 한다(UnpaidOrderRule) */
    private void doneUnpaidOrder(Long sessionId, LocalDate businessDate) {
        OrderEntity order = unpaidOrder(sessionId, businessDate);
        assertEquals(1, orderRepository.markDone(order.getId(), booth.getId()));
    }

    /** 완료·입금까지 끝난 주문 — 받을 돈이 없으니 세션을 붙잡지 않는다 */
    private void donePaidOrder(Long sessionId, LocalDate businessDate) {
        OrderEntity order = unpaidOrder(sessionId, businessDate);
        assertEquals(1, orderRepository.markDone(order.getId(), booth.getId()));
        assertEquals(1, orderRepository.markPaid(order.getId(), booth.getId(), PaymentMethod.BANK_TRANSFER, "admin", EVENING));
    }

    // ── C1 유휴 만료 ─────────────────────────────────────

    @Test
    void c1DoesNotHandOverIdleSessionWithoutUnpaidOrders() throws Exception {
        TableEntity table = occupiedTable("A-1");
        TableSessionEntity old = sessionLastActiveAt(table, "sess-previous-guest", EVENING.minusHours(5));

        String newToken = scan(table.getTableToken(), false);

        assertNotEquals("sess-previous-guest", newToken);
        TableSessionEntity ended = tableSessionRepository.findById(old.getId()).orElseThrow();
        assertEquals(EVENING, ended.getEndedAt());                // 스캔 시각(정책 시계)으로 종료
        assertEquals(ended.getId(), ended.getEndedAtKey());
        assertEquals(TableStatus.OCCUPIED, tableRepository.findById(table.getId()).orElseThrow().getStatus());
        assertEquals(1, tableSessionRepository.findOpenByTableIds(List.of(table.getId())).size());

        // 앞 손님 토큰은 이제 410 — 새 손님은 앞 손님 주문을 볼 수 없다
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", "sess-previous-guest"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(0));
    }

    @Test
    void c1RestoresIdleSessionThatHoldsUnpaidOrderOfCurrentBusinessDay() throws Exception {
        TableEntity table = occupiedTable("A-1");
        TableSessionEntity session = sessionLastActiveAt(table, "sess-unpaid", EVENING.minusHours(5));
        unpaidOrder(session.getId(), DAY_ONE);

        assertEquals("sess-unpaid", scan(table.getTableToken(), true));
        assertNull(tableSessionRepository.findById(session.getId()).orElseThrow().getEndedAt());
    }

    @Test
    void c1RestoresIdleSessionThatHoldsDoneButUnpaidOrder() throws Exception {
        // 완료 처리만 되고 입금이 안 된 주문도 미수금 — 유휴 세션을 붙잡는다(팀 결정: 미결제 = 서빙과 무관하게 입금 안 됨)
        TableEntity table = occupiedTable("A-1");
        TableSessionEntity session = sessionLastActiveAt(table, "sess-done-unpaid", EVENING.minusHours(5));
        doneUnpaidOrder(session.getId(), DAY_ONE);

        assertEquals("sess-done-unpaid", scan(table.getTableToken(), true));
        assertNull(tableSessionRepository.findById(session.getId()).orElseThrow().getEndedAt());
    }

    @Test
    void c1ExpiresUnpaidSessionOnceBusinessDayHasTurned() throws Exception {
        TableEntity table = occupiedTable("A-1");
        TableSessionEntity session = sessionLastActiveAt(table, "sess-yesterday", EVENING.minusHours(1));
        unpaidOrder(session.getId(), DAY_ONE);

        CLOCK.setKst(LocalDateTime.of(2026, 9, 16, 5, 59, 59));   // 아직 2026-09-15 영업일 — 미결제가 세션을 붙잡는다
        assertEquals("sess-yesterday", scan(table.getTableToken(), true));
        // 위 복원이 활동을 05:59:59로 갱신했다. 영업일이 바뀌면 그 활동도 곧 유휴가 되도록 임계 밖으로 시계를 옮긴다
        CLOCK.setKst(LocalDateTime.of(2026, 9, 16, 9, 0, 0));      // 2026-09-16 영업일, 활동은 3시간 1초 전

        String newToken = scan(table.getTableToken(), false);
        assertNotEquals("sess-yesterday", newToken);
        assertNotNull(tableSessionRepository.findById(session.getId()).orElseThrow().getEndedAt());
    }

    @Test
    void c1ExpiresUnpaidSessionExactlyAtSixAm() throws Exception {
        TableEntity table = occupiedTable("A-1");
        TableSessionEntity session = sessionLastActiveAt(table, "sess-six", LocalDateTime.of(2026, 9, 16, 2, 0));
        unpaidOrder(session.getId(), DAY_ONE);

        CLOCK.setKst(LocalDateTime.of(2026, 9, 16, 6, 0, 0));   // 영업일 경계 정각, 활동은 4시간 전

        assertNotEquals("sess-six", scan(table.getTableToken(), false));
    }

    @Test
    void concurrentScansOnIdleSessionEndItOnceAndOpenExactlyOneNewSession() throws Exception {
        for (int round = 0; round < 15; round++) {
            TableEntity table = occupiedTable("R" + round);
            TableSessionEntity old = sessionLastActiveAt(table, "sess-race-" + round, EVENING.minusHours(5));
            TableSessionCreateRequest request = new TableSessionCreateRequest(table.getTableToken());

            List<Object> results = race(4, () -> tableSessionService.createOrRestore(request));

            List<TableSessionResponse> responses = new ArrayList<>();
            for (Object result : results) {
                assertTrue(result instanceof TableSessionResponse, "동시 스캔 중 예외: " + result);
                responses.add((TableSessionResponse) result);
            }
            assertEquals(1, responses.stream().filter(r -> !r.restored()).count(), "새 세션은 정확히 한 번 만들어져야 한다");
            assertEquals(1, responses.stream().map(TableSessionResponse::sessionToken).distinct().count(), "모두 같은 새 세션을 받아야 한다");
            String oldToken = old.getSessionToken();
            assertTrue(responses.stream().noneMatch(r -> r.sessionToken().equals(oldToken)));

            TableSessionEntity ended = tableSessionRepository.findById(old.getId()).orElseThrow();
            assertNotNull(ended.getEndedAt());
            assertEquals(ended.getId(), ended.getEndedAtKey());
            assertEquals(1, tableSessionRepository.findOpenByTableIds(List.of(table.getId())).size());
        }
    }

    // ── 세 곳 교차 확인 ──────────────────────────────────

    @Test
    void c1O3AndE1AgreeOnWhichTablesAreActive() throws Exception {
        TableEntity recent = occupiedTable("A-1");            // 5분 전 활동 — 활성
        sessionLastActiveAt(recent, "sess-recent", EVENING.minusMinutes(5));
        TableEntity idle = occupiedTable("A-2");              // 5시간 유휴, 미결제 없음 — 비활성
        sessionLastActiveAt(idle, "sess-idle", EVENING.minusHours(5));
        TableEntity idleUnpaidToday = occupiedTable("A-3");   // 5시간 유휴, 오늘 미결제 — 활성
        unpaidOrder(sessionLastActiveAt(idleUnpaidToday, "sess-unpaid-today", EVENING.minusHours(5)).getId(), DAY_ONE);
        TableEntity idleUnpaidOld = occupiedTable("A-4");     // 5시간 유휴, 지난 영업일 미결제 — 비활성
        unpaidOrder(sessionLastActiveAt(idleUnpaidOld, "sess-unpaid-old", EVENING.minusHours(5)).getId(), DAY_ONE.minusDays(1));
        occupiedTable("A-5");                                 // 세션 없음 — 비활성
        TableEntity idleDoneUnpaid = occupiedTable("A-6");    // 5시간 유휴, 오늘 완료·미입금 — 미수금이라 활성 (UnpaidOrderRule)
        doneUnpaidOrder(sessionLastActiveAt(idleDoneUnpaid, "sess-done-unpaid", EVENING.minusHours(5)).getId(), DAY_ONE);
        TableEntity idleDonePaid = occupiedTable("A-7");      // 5시간 유휴, 오늘 완료·입금 — 비활성
        donePaidOrder(sessionLastActiveAt(idleDonePaid, "sess-done-paid", EVENING.minusHours(5)).getId(), DAY_ONE);

        List<String> labels = List.of("A-1", "A-2", "A-3", "A-4", "A-5", "A-6", "A-7");
        List<Boolean> expectedActive = List.of(true, false, true, false, false, true, false);

        // E1 — 비활성 테이블 수 = 빈자리 수
        mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(jsonPath("$.booths[0].tables.total").value(7))
                .andExpect(jsonPath("$.booths[0].tables.empty").value(4));

        // O3 — 활성이면 session 객체·needsCleanup false, 비활성이면 session null·needsCleanup true
        String body = mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode tables = objectMapper.readTree(body).get("tables");
        List<Boolean> o3Active = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            JsonNode item = tables.get(i);
            assertEquals(labels.get(i), item.get("label").asString());
            boolean active = !item.get("session").isNull();
            assertEquals(!active, item.get("needsCleanup").asBoolean(), labels.get(i) + " needsCleanup");
            o3Active.add(active);
        }
        assertEquals(expectedActive, o3Active, "O3 판정");

        // C1 — 활성이면 복원, 비활성이면 새 발급(세션 없음 포함)
        List<Boolean> c1Restored = new ArrayList<>();
        for (String label : labels) {
            String tableToken = "tok-idle-" + label;
            String response = mockMvc.perform(post("/api/v1/table-sessions").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tableToken\":\"" + tableToken + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            c1Restored.add(objectMapper.readTree(response).get("restored").asBoolean());
        }
        assertEquals(expectedActive, c1Restored, "C1 판정");
        assertEquals(o3Active, c1Restored);
    }

    @Test
    void o3AndE1TreatIdleUnpaidSessionAsActiveUntilSixAm() throws Exception {
        TableEntity table = occupiedTable("A-1");
        unpaidOrder(sessionLastActiveAt(table, "sess-late", LocalDateTime.of(2026, 9, 16, 1, 0)).getId(), DAY_ONE);
        String token = login();

        CLOCK.setKst(LocalDateTime.of(2026, 9, 16, 5, 59, 59));
        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.tables[0].session").exists())
                .andExpect(jsonPath("$.tables[0].needsCleanup").value(false))
                .andExpect(jsonPath("$.tables[0].unpaidOrderCount").value(1));
        mockMvc.perform(get("/api/v1/event/booths")).andExpect(jsonPath("$.booths[0].tables.empty").value(0));

        CLOCK.setKst(LocalDateTime.of(2026, 9, 16, 6, 0, 0));
        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.tables[0].session").value(nullValue()))
                .andExpect(jsonPath("$.tables[0].needsCleanup").value(true))
                .andExpect(jsonPath("$.tables[0].unpaidOrderCount").value(1));   // 건수 표시는 영업일과 무관 — 퇴실 전 확인용
        mockMvc.perform(get("/api/v1/event/booths")).andExpect(jsonPath("$.booths[0].tables.empty").value(1));
    }

    // ── 도우미 ───────────────────────────────────────────

    private String scan(String tableToken, boolean expectRestored) throws Exception {
        String body = mockMvc.perform(post("/api/v1/table-sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"" + tableToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(expectRestored))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("sessionToken").asString();
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"admin\",\"password\":\"password\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private static List<Object> race(int threads, Callable<?> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return task.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            ready.await();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
