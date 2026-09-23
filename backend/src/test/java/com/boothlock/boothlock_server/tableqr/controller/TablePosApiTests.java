package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableStatus;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 운영자 POS 화면의 테이블 파트 — O3 응답의 유휴 판정·O22 배치 좌표 응답 형태·O6 퇴실 (명세서 O3·O6·O22, v0.5.1 합집합 안).
 * 조건부 UPDATE가 즉시 커밋되므로 @Transactional 롤백을 쓰지 않고 직접 정리한다.
 * 유휴 임계 ±1초 경계와 쿼리 수는 시계를 고정한 TableStatusBoundaryTests에서, O22 입력 검증은 TablePositionValidationTests에서 본다.
 */
@SpringBootTest(properties = "boothlock.event.booths-cache-seconds=0")
// 홈 화면 목록(E1)의 10초 캐시를 끈다 — 퇴실·유휴 판정 직후 E1 결과를 바로 비교하므로 캐시가 켜져 있으면 앞 결과가 보인다
@AutoConfigureMockMvc
class TablePosApiTests {

    /** 프로덕션 코드가 전부 KST로 시각을 만든다 — 시딩도 같은 기준이어야 build.gradle의 시간대 고정에 기대지 않는다 */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired DailyCounterRepository dailyCounterRepository;
    @Autowired MenuRepository menuRepository;

    private BoothEntity booth;
    private BoothEntity otherBooth;
    private int orderSeq;

    @BeforeEach
    void setUp() {
        cleanUp();
        booth = boothRepository.save(new BoothEntity("POS 부스", "은행 1234", null));
        otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staffRepository.save(new StaffAccountEntity(booth, "admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        staffRepository.save(new StaffAccountEntity(otherBooth, "other-admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.STAFF));
        orderSeq = 0;
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        orderRepository.deleteAll();
        dailyCounterRepository.deleteAll();
        menuRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffRepository.deleteAll();
        boothRepository.deleteAll();
    }

    // ── 시딩 도우미 ─────────────────────────────────────

    private static LocalDateTime now() {
        return LocalDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS);
    }

    private TableEntity table(BoothEntity owner, String label, boolean occupied) {
        TableEntity table = new TableEntity(owner, label, "tok-" + owner.getId() + "-" + label);
        if (occupied) {
            table.occupy();
        }
        return tableRepository.save(table);
    }

    /** startedAt에 시작해 lastActivityAt에 마지막으로 활동한 열린 세션 */
    private TableSessionEntity openSession(TableEntity table, String token, LocalDateTime startedAt, LocalDateTime lastActivityAt) {
        TableSessionEntity session = tableSessionRepository.save(new TableSessionEntity(table, token, startedAt));
        tableSessionRepository.touchIfActive(session.getId(), lastActivityAt);
        return session;
    }

    private TableSessionEntity endedSession(TableEntity table, String token) {
        TableSessionEntity session = tableSessionRepository.save(new TableSessionEntity(table, token, now().minusHours(2)));
        session.end(now().minusHours(1));
        return tableSessionRepository.save(session);
    }

    /**
     * 영업일은 일부러 먼 과거로 둔다 — 유휴 정책은 "현재 영업일의 미결제"가 있으면 유휴 세션도 활성으로 보는데(§7-9),
     * 이 클래스는 실제 시계를 쓰므로 영업일을 오늘로 두면 실행 시각에 따라 판정이 달라진다.
     * 미결제 예외·영업일 경계는 시계를 고정한 SeatIdleConsistencyTests에서 본다
     */
    private OrderEntity unpaidOrder(BoothEntity owner, Long sessionId) {
        orderSeq++;
        return orderRepository.save(new OrderEntity(owner.getId(), sessionId, "X" + orderSeq, LocalDate.of(2020, 1, 1),
                orderSeq, "idem-pos-" + orderSeq, 8000, false, now()));
    }

    private void canceledUnpaidOrder(BoothEntity owner, Long sessionId) {
        OrderEntity order = unpaidOrder(owner, sessionId);
        assertEquals(1, orderRepository.cancelByStaff(order.getId(), owner.getId(), "테스트", "admin", now()));
    }

    private void paidOrder(BoothEntity owner, Long sessionId) {
        OrderEntity order = unpaidOrder(owner, sessionId);
        assertEquals(1, orderRepository.markPaid(order.getId(), owner.getId(), PaymentMethod.CASH, "admin", now()));
    }

    private void doneUnpaidOrder(BoothEntity owner, Long sessionId) {
        OrderEntity order = unpaidOrder(owner, sessionId);
        assertEquals(1, orderRepository.markDone(order.getId(), owner.getId()));
    }

    private TableSessionEntity reloadSession(Long id) {
        return tableSessionRepository.findById(id).orElseThrow();
    }

    private TableEntity reloadTable(Long id) {
        return tableRepository.findById(id).orElseThrow();
    }

    // ── O3 좌석 현황 — 응답 확장 ────────────────────────

    @Test
    void o3KeepsLegacyFieldsAndAddsPositionSessionAndUnpaidCount() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        table.updatePosition(120, 240);
        table = tableRepository.save(table);
        LocalDateTime startedAt = now().minusMinutes(40);
        LocalDateTime lastActivityAt = now().minusMinutes(3);
        TableSessionEntity session = openSession(table, "sess-a1", startedAt, lastActivityAt);
        unpaidOrder(booth, session.getId());

        String body = mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.length()").value(1))
                .andExpect(jsonPath("$.tables[0].id").value(table.getId()))
                .andExpect(jsonPath("$.tables[0].label").value("A-1"))
                .andExpect(jsonPath("$.tables[0].status").value("OCCUPIED"))
                .andExpect(jsonPath("$.tables[0].needsCleanup").value(false))
                .andExpect(jsonPath("$.tables[0].posX").value(120))
                .andExpect(jsonPath("$.tables[0].posY").value(240))
                .andExpect(jsonPath("$.tables[0].session.startedAt").value(startedAt.atOffset(ZoneOffset.ofHours(9)).format(OFFSET)))
                .andExpect(jsonPath("$.tables[0].session.lastActivityAt").value(lastActivityAt.atOffset(ZoneOffset.ofHours(9)).format(OFFSET)))
                .andExpect(jsonPath("$.tables[0].unpaidOrderCount").value(1))
                .andReturn().getResponse().getContentAsString();

        // 프론트(TableOrderContext·TableQrPage)가 읽는 필드의 JSON 타입이 그대로인지 — 이름만 같고 타입이 바뀌어도 화면이 깨진다
        JsonNode item = objectMapper.readTree(body).get("tables").get(0);
        assertTrue(item.get("id").isIntegralNumber());
        assertTrue(item.get("label").isString());
        assertTrue(item.get("status").isString());
        assertTrue(item.get("needsCleanup").isBoolean());
        assertTrue(item.get("posX").isIntegralNumber());
        assertTrue(item.get("unpaidOrderCount").isIntegralNumber());
        assertTrue(item.get("session").get("startedAt").asString().endsWith("+09:00"));
    }

    @Test
    void o3UnplacedTableHasNullPositionAndNullSession() throws Exception {
        table(booth, "A-1", false);

        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0].posX").value(nullValue()))
                .andExpect(jsonPath("$.tables[0].posY").value(nullValue()))
                .andExpect(jsonPath("$.tables[0].session").value(nullValue()))
                .andExpect(jsonPath("$.tables[0].unpaidOrderCount").value(0))
                .andExpect(jsonPath("$.tables[0].status").value("EMPTY"))
                .andExpect(jsonPath("$.tables[0].needsCleanup").value(false));
    }

    @Test
    void o3IdleSessionIsNotActiveSoTableNeedsCleanupButUnpaidStillCounts() throws Exception {
        // E1이 빈자리로 세는 테이블(마지막 활동 5시간 전)을 O3도 "정리 필요"로 보여야 두 화면이 같은 말을 한다.
        // 미결제 2건은 지난 영업일 주문이라 세션을 붙잡지 않는다(유휴 판정엔 영향 없음) — 그래도 퇴실 전 확인용 건수에는 잡힌다
        TableEntity idle = table(booth, "A-1", true);
        TableSessionEntity idleSession = openSession(idle, "sess-idle", now().minusHours(6), now().minusHours(5));
        unpaidOrder(booth, idleSession.getId());
        unpaidOrder(booth, idleSession.getId());
        TableEntity active = table(booth, "A-2", true);
        openSession(active, "sess-active", now().minusHours(1), now().minusMinutes(5));

        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0].label").value("A-1"))
                .andExpect(jsonPath("$.tables[0].session").value(nullValue()))
                .andExpect(jsonPath("$.tables[0].needsCleanup").value(true))
                .andExpect(jsonPath("$.tables[0].unpaidOrderCount").value(2))   // 유휴여도 퇴실 전 확인할 미결제는 남아 있다
                .andExpect(jsonPath("$.tables[1].label").value("A-2"))
                .andExpect(jsonPath("$.tables[1].session").exists())
                .andExpect(jsonPath("$.tables[1].needsCleanup").value(false));

        // 같은 순간 E1은 A-1을 빈자리로 센다 — 기준이 같다는 교차 확인
        mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(jsonPath("$.booths[0].name").value("POS 부스"))
                .andExpect(jsonPath("$.booths[0].tables.total").value(2))
                .andExpect(jsonPath("$.booths[0].tables.empty").value(1));
    }

    @Test
    void o3OccupiedWithOnlyEndedSessionsNeedsCleanup() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        endedSession(table, "sess-ended");

        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + login("admin")))
                .andExpect(jsonPath("$.tables[0].session").value(nullValue()))
                .andExpect(jsonPath("$.tables[0].needsCleanup").value(true))
                .andExpect(jsonPath("$.tables[0].unpaidOrderCount").value(0));
    }

    @Test
    void o3CountsOnlyOpenSessionUnpaidAcrossEndedHistory() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        // 과거 손님 두 팀 — 각자 미결제를 남기고 퇴실했다. 지금 손님 자리에 붙어 보이면 안 된다
        TableSessionEntity past1 = endedSession(table, "sess-past-1");
        TableSessionEntity past2 = endedSession(table, "sess-past-2");
        unpaidOrder(booth, past1.getId());
        unpaidOrder(booth, past2.getId());
        LocalDateTime startedAt = now().minusMinutes(20);
        TableSessionEntity current = openSession(table, "sess-current", startedAt, now().minusMinutes(1));
        unpaidOrder(booth, current.getId());            // 센다
        canceledUnpaidOrder(booth, current.getId());    // 취소된 미결제 — 빼야 한다
        paidOrder(booth, current.getId());              // 입금 완료 — 빼야 한다
        doneUnpaidOrder(booth, current.getId());        // 완료 처리됐지만 미입금 — 서빙과 무관하게 미수금이라 센다(UnpaidOrderRule)
        unpaidOrder(booth, null);                       // 수기 주문(세션 없음) — 어느 테이블에도 붙지 않는다

        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + login("admin")))
                .andExpect(jsonPath("$.tables[0].session.startedAt").value(startedAt.atOffset(ZoneOffset.ofHours(9)).format(OFFSET)))
                .andExpect(jsonPath("$.tables[0].session.id").value(current.getId().intValue()))
                .andExpect(jsonPath("$.tables[0].unpaidOrderCount").value(2));
    }

    @Test
    void o3UnpaidCountsZeroOneAndManyPerTable() throws Exception {
        TableEntity zero = table(booth, "A-1", true);
        openSession(zero, "sess-0", now().minusMinutes(30), now().minusMinutes(1));
        TableEntity one = table(booth, "A-2", true);
        TableSessionEntity oneSession = openSession(one, "sess-1", now().minusMinutes(30), now().minusMinutes(1));
        unpaidOrder(booth, oneSession.getId());
        TableEntity many = table(booth, "A-3", true);
        TableSessionEntity manySession = openSession(many, "sess-3", now().minusMinutes(30), now().minusMinutes(1));
        unpaidOrder(booth, manySession.getId());
        unpaidOrder(booth, manySession.getId());
        unpaidOrder(booth, manySession.getId());

        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + login("admin")))
                .andExpect(jsonPath("$.tables[0].unpaidOrderCount").value(0))
                .andExpect(jsonPath("$.tables[1].unpaidOrderCount").value(1))
                .andExpect(jsonPath("$.tables[2].unpaidOrderCount").value(3));
    }

    @Test
    void o3DoesNotMixOtherBoothsTablesOrOrders() throws Exception {
        table(booth, "A-1", false);
        TableEntity foreign = table(otherBooth, "A-1", true);
        TableSessionEntity foreignSession = openSession(foreign, "sess-foreign", now().minusMinutes(10), now());
        unpaidOrder(otherBooth, foreignSession.getId());

        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + login("admin")))
                .andExpect(jsonPath("$.tables.length()").value(1))
                .andExpect(jsonPath("$.tables[0].status").value("EMPTY"))
                .andExpect(jsonPath("$.tables[0].unpaidOrderCount").value(0));
    }

    // ── O22 배치 좌표 저장 ──────────────────────────────

    @Test
    void o22SavesPositionAndReturnsExactlyTheO3ItemShape() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(2));
        unpaidOrder(booth, session.getId());
        String token = login("admin");

        String patched = mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", table.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"posX\":120,\"posY\":240}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(table.getId()))
                .andExpect(jsonPath("$.posX").value(120))
                .andExpect(jsonPath("$.posY").value(240))
                .andExpect(jsonPath("$.unpaidOrderCount").value(1))
                .andExpect(jsonPath("$.needsCleanup").value(false))
                .andReturn().getResponse().getContentAsString();

        TableEntity reloaded = reloadTable(table.getId());
        assertEquals(120, reloaded.getPosX());
        assertEquals(240, reloaded.getPosY());
        // 좌표는 표시 전용 — 토큰·상태·세션을 건드리면 안 된다
        assertEquals("tok-" + booth.getId() + "-A-1", reloaded.getTableToken());
        assertEquals(TableStatus.OCCUPIED, reloaded.getStatus());
        assertNull(reloadSession(session.getId()).getEndedAt());

        String listed = mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode patchedNode = objectMapper.readTree(patched);
        JsonNode listedNode = objectMapper.readTree(listed).get("tables").get(0);
        // 필드 집합뿐 아니라 값까지 같다 — 클라이언트가 두 벌로 파싱할 일이 없다
        assertEquals(fieldNames(listedNode), fieldNames(patchedNode));
        assertEquals(listedNode, patchedNode);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void o22HidesOtherBoothsTableAsNotFound() throws Exception {
        TableEntity foreign = table(otherBooth, "Z-1", false);

        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", foreign.getId())
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"posX\":1,\"posY\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertNull(reloadTable(foreign.getId()).getPosX());
    }

    @Test
    void o22UnknownTableIsNotFound() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", 999_999L)
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"posX\":1,\"posY\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void o22RequiresValidOperatorToken() throws Exception {
        TableEntity table = table(booth, "A-1", false);

        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", table.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"posX\":1,\"posY\":2}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", table.getId())
                        .header("Authorization", "Bearer not-a-jwt")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"posX\":1,\"posY\":2}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertNull(reloadTable(table.getId()).getPosX());
    }

    // ── O6 퇴실 ─────────────────────────────────────────

    @Test
    void o6EndsOpenSessionEmptiesTableAndOmitsWarningWhenNothingUnpaid() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        paidOrder(booth, session.getId());
        canceledUnpaidOrder(booth, session.getId());

        String body = mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaidWarning").value(false))
                .andExpect(jsonPath("$.id").value(table.getId()))
                .andExpect(jsonPath("$.label").value("A-1"))
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.warning").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        // 프론트가 이미 받는 unpaidWarning은 유지하고 명세 O6의 id·label·status를 더한다. warning은 미결제 없으면 필드째 없다
        assertEquals(Set.of("unpaidWarning", "id", "label", "status", "completedOrderCount"), fieldNames(objectMapper.readTree(body)));

        TableSessionEntity ended = reloadSession(session.getId());
        assertNotNull(ended.getEndedAt());
        assertEquals(ended.getId(), ended.getEndedAtKey());
        assertEquals(TableStatus.EMPTY, reloadTable(table.getId()).getStatus());
        assertEquals(2, orderRepository.count());   // 주문은 삭제하지 않는다(정산 보존)
    }

    @Test
    void o6WarnsWithUnpaidCountOfTheOpenSession() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity past = endedSession(table, "sess-past");
        unpaidOrder(booth, past.getId());               // 앞 손님 미결제 — 이번 퇴실 경고에 섞이면 안 된다
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        unpaidOrder(booth, session.getId());
        unpaidOrder(booth, session.getId());
        unpaidOrder(booth, session.getId());
        canceledUnpaidOrder(booth, session.getId());    // 취소된 미입금 — 받을 돈이 없어 경고에서 뺀다
        paidOrder(booth, session.getId());              // 입금 완료 — 뺀다
        doneUnpaidOrder(booth, session.getId());        // 완료 처리됐지만 미입금 — 미수금이라 경고에 넣는다(UnpaidOrderRule)

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaidWarning").value(true))
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.warning").value("미결제 주문 4건 있음"));

        assertNotNull(reloadSession(session.getId()).getEndedAt());   // 경고만 하고 차단하지 않는다
    }

    /**
     * 퇴실하면 그 손님(종료한 세션)의 남은 접수 주문은 완료로 넘어간다 — 후결제에서 나간 손님 주문이 주문현황 접수 탭에 남지 않게.
     * 입금 상태는 그대로, 취소·완료 주문과 앞 손님·다른 부스 주문은 건드리지 않는다
     */
    @Test
    void o6CompletesRemainingReceivedOrdersOfTheEndingSessionOnly() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity past = endedSession(table, "sess-past");
        OrderEntity pastReceived = unpaidOrder(booth, past.getId());   // 앞 손님 접수 주문 — 이번 퇴실과 무관
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        OrderEntity receivedUnpaid = unpaidOrder(booth, session.getId());
        OrderEntity receivedPaid = unpaidOrder(booth, session.getId());
        assertEquals(1, orderRepository.markPaid(receivedPaid.getId(), booth.getId(), PaymentMethod.CASH, "admin", now()));
        canceledUnpaidOrder(booth, session.getId());
        doneUnpaidOrder(booth, session.getId());
        TableEntity foreign = table(otherBooth, "B-1", true);
        TableSessionEntity foreignSession = openSession(foreign, "sess-b1", now().minusMinutes(30), now().minusMinutes(1));
        OrderEntity foreignReceived = unpaidOrder(otherBooth, foreignSession.getId());

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedOrderCount").value(2))
                .andExpect(jsonPath("$.warning").value("미결제 주문 2건 있음"));   // 완료로 넘겨도 미입금은 미수금 그대로

        OrderEntity reloadedUnpaid = orderRepository.findById(receivedUnpaid.getId()).orElseThrow();
        assertEquals(OrderStatus.DONE, reloadedUnpaid.getStatus());
        assertEquals(PaymentStatus.UNPAID, reloadedUnpaid.getPaymentStatus());
        OrderEntity reloadedPaid = orderRepository.findById(receivedPaid.getId()).orElseThrow();
        assertEquals(OrderStatus.DONE, reloadedPaid.getStatus());
        assertEquals(PaymentStatus.PAID, reloadedPaid.getPaymentStatus());
        assertEquals(OrderStatus.RECEIVED, orderRepository.findById(pastReceived.getId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.RECEIVED, orderRepository.findById(foreignReceived.getId()).orElseThrow().getStatus());
        assertEquals(1, orderRepository.findAll().stream().filter(o -> o.getStatus() == OrderStatus.CANCELED).count());
        assertEquals(TableStatus.EMPTY, reloadTable(table.getId()).getStatus());
    }

    /** 유휴로 만료된("정리 필요") 세션도 퇴실이 종료하므로 그 접수 주문도 완료로 넘어간다. 멱등 재호출은 0건 */
    @Test
    void o6CompletesReceivedOrdersOfIdleSessionAndSecondCallCompletesNothing() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity idle = openSession(table, "sess-idle", now().minusHours(6), now().minusHours(5));
        OrderEntity order = unpaidOrder(booth, idle.getId());
        String token = login("admin");

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedOrderCount").value(1));
        assertEquals(OrderStatus.DONE, orderRepository.findById(order.getId()).orElseThrow().getStatus());

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedOrderCount").value(0));
    }

    @Test
    void o6WarnsForSingleUnpaidOrder() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        unpaidOrder(booth, session.getId());

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaidWarning").value(true))
                .andExpect(jsonPath("$.warning").value("미결제 주문 1건 있음"));
    }

    @Test
    void o6EndsIdleSessionToo() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity idle = openSession(table, "sess-idle", now().minusHours(6), now().minusHours(5));
        unpaidOrder(booth, idle.getId());

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaidWarning").value(true))
                .andExpect(jsonPath("$.warning").value("미결제 주문 1건 있음"));

        assertNotNull(reloadSession(idle.getId()).getEndedAt());
        assertEquals(TableStatus.EMPTY, reloadTable(table.getId()).getStatus());
    }

    @Test
    void o6IsIdempotent() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        String token = login("admin");

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        LocalDateTime firstEndedAt = reloadSession(session.getId()).getEndedAt();

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaidWarning").value(false))
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.warning").doesNotExist());

        // 두 번째 퇴실이 첫 종료 기록을 덮어쓰지 않는다
        assertEquals(firstEndedAt, reloadSession(session.getId()).getEndedAt());
        assertEquals(1, tableSessionRepository.count());
    }

    /** "정리 필요"(OCCUPIED인데 세션 없음) 테이블도 퇴실로 비울 수 있어야 한다 — 410이면 영영 사용중으로 남는다 */
    @Test
    void o6ClearsOccupiedTableWithoutAnySession() throws Exception {
        TableEntity table = table(booth, "A-1", true);

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaidWarning").value(false))
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.warning").doesNotExist());

        assertEquals(TableStatus.EMPTY, reloadTable(table.getId()).getStatus());
    }

    @Test
    void o6OnEmptyTableWithoutAnySessionReturnsOk() throws Exception {
        TableEntity table = table(booth, "A-1", false);

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.warning").doesNotExist());
    }

    @Test
    void o6HidesOtherBoothsTableAndLeavesItUntouched() throws Exception {
        TableEntity foreign = table(otherBooth, "Z-1", true);
        TableSessionEntity foreignSession = openSession(foreign, "sess-foreign", now().minusMinutes(10), now());

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", foreign.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertNull(reloadSession(foreignSession.getId()).getEndedAt());
        assertEquals(TableStatus.OCCUPIED, reloadTable(foreign.getId()).getStatus());
    }

    @Test
    void o6UnknownTableIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", 999_999L)
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void o6RequiresValidOperatorToken() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(10), now());

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        assertNull(reloadSession(session.getId()).getEndedAt());
        assertEquals(TableStatus.OCCUPIED, reloadTable(table.getId()).getStatus());
    }

    @Test
    void afterCheckoutOldSessionTokenGets410OnC2C3C4AndRescanIssuesNewSession() throws Exception {
        TableEntity table = table(booth, "A-1", false);
        MenuEntity menu = menuRepository.save(new MenuEntity(booth, "김치전", 8000, null, null, true));
        String oldToken = scan(table.getTableToken(), false);

        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", oldToken)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/menus").header("X-Session-Token", oldToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
        mockMvc.perform(post("/api/v1/orders").header("X-Session-Token", oldToken)
                        .header("Idempotency-Key", "idem-after-checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"menuId\":" + menu.getId() + ",\"qty\":1}]}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", oldToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
        assertEquals(0, orderRepository.count());

        // 다음 손님이 QR을 찍으면 복원이 아니라 새 세션이다
        String newToken = scan(table.getTableToken(), false);
        assertNotEquals(oldToken, newToken);
        assertEquals(TableStatus.OCCUPIED, reloadTable(table.getId()).getStatus());
        assertEquals(1, tableSessionRepository.findAll().stream().filter(s -> s.getEndedAt() == null).count());
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", newToken)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", oldToken)).andExpect(status().isGone());

        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + login("admin")))
                .andExpect(jsonPath("$.tables[0].status").value("OCCUPIED"))
                .andExpect(jsonPath("$.tables[0].session").exists())
                .andExpect(jsonPath("$.tables[0].needsCleanup").value(false));
    }

    @Test
    void checkoutReflectsOnHomeScreenImmediately() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        openSession(table, "sess-a1", now().minusMinutes(10), now());

        mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(jsonPath("$.booths[0].tables.empty").value(0));
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(jsonPath("$.booths[0].tables.empty").value(1));
    }

    // ── O6 requireSettled ("결제 완료" 버튼: O24 → O6 사이 끼어든 주문 보호) ───────────

    /** O24 일괄 입금 뒤 O6 전에 들어온 미결제 주문 — 409로 전체 롤백: 세션은 열린 채, 주문은 접수 상태 그대로, 테이블은 사용중 */
    @Test
    void o6RequireSettledRejectsWhenUnpaidOrderRemainsAndChangesNothing() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        paidOrder(booth, session.getId());                          // O24가 입금 확인한 주문
        OrderEntity lateOrder = unpaidOrder(booth, session.getId()); // O24와 O6 사이에 들어온 새 주문

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .param("requireSettled", "true")
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHECKOUT_UNPAID_REMAINS"))
                .andExpect(jsonPath("$.error.details.unpaidOrderCount").value(1));

        assertNull(reloadSession(session.getId()).getEndedAt());
        assertEquals(0, reloadSession(session.getId()).getEndedAtKey());
        assertEquals(TableStatus.OCCUPIED, reloadTable(table.getId()).getStatus());
        OrderEntity reloaded = orderRepository.findById(lateOrder.getId()).orElseThrow();
        assertEquals(OrderStatus.RECEIVED, reloaded.getStatus());      // 주방 대기열에서 사라지지 않았다
        assertEquals(PaymentStatus.UNPAID, reloaded.getPaymentStatus());
        // 손님 토큰도 그대로 살아 있다
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", "sess-a1")).andExpect(status().isOk());
    }

    @Test
    void o6RequireSettledSucceedsWhenEverythingIsPaid() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        paidOrder(booth, session.getId());
        canceledUnpaidOrder(booth, session.getId());   // 취소된 미입금은 미결제가 아니다(UnpaidOrderRule)

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .param("requireSettled", "true")
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaidWarning").value(false))
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.warning").doesNotExist());

        assertNotNull(reloadSession(session.getId()).getEndedAt());
        assertEquals(TableStatus.EMPTY, reloadTable(table.getId()).getStatus());
    }

    /** 파라미터를 안 주면("테이블 비우기") 예전처럼 막지 않고 warning만 — 남은 접수 주문은 완료로 넘어간다 */
    @Test
    void o6WithoutRequireSettledStillOnlyWarnsOnUnpaidOrder() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        OrderEntity unpaid = unpaidOrder(booth, session.getId());

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaidWarning").value(true))
                .andExpect(jsonPath("$.warning").value("미결제 주문 1건 있음"))
                .andExpect(jsonPath("$.completedOrderCount").value(1));

        assertNotNull(reloadSession(session.getId()).getEndedAt());
        assertEquals(OrderStatus.DONE, orderRepository.findById(unpaid.getId()).orElseThrow().getStatus());
    }

    private String scan(String tableToken, boolean expectRestored) throws Exception {
        String body = mockMvc.perform(post("/api/v1/table-sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"" + tableToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(expectRestored))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("sessionToken").asString();
    }

    private String login(String loginId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Credentials(loginId, "password"))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private record Credentials(String loginId, String password) {
    }
}
