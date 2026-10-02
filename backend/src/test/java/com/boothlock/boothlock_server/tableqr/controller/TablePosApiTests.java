package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.dashboard.domain.CallReason;
import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderWriter;
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
    @Autowired OrderWriter orderWriter;
    @Autowired StaffCallRepository staffCallRepository;

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
        staffCallRepository.deleteAll();   // 세션을 참조(FK)하므로 세션보다 먼저 — 합석 테스트가 호출을 만든다
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

    /** O28 승인대기(v0.6.10) 주문 — 손님 주문(C3)이 아직 운영자 승인을 못 받은 상태를 직접 만든다 */
    private OrderEntity pendingApprovalOrder(BoothEntity owner, Long sessionId) {
        orderSeq++;
        OrderEntity order = new OrderEntity(owner.getId(), sessionId, "X" + orderSeq, LocalDate.of(2020, 1, 1),
                orderSeq, "idem-pos-" + orderSeq, 8000, false, now());
        order.startPendingApproval();
        return orderRepository.save(order);
    }

    /** 첫 주문 때 자동으로 생기는 자릿세 전용 주문 — 조리할 게 없어 DONE+UNPAID로 시작한다(OrderWriter.saveSeatFeeOrder) */
    private OrderEntity seatFeeOrder(BoothEntity owner, Long sessionId) {
        orderSeq++;
        OrderEntity order = new OrderEntity(owner.getId(), sessionId, "X" + orderSeq, LocalDate.of(2020, 1, 1),
                orderSeq, null, 6000, false, now());
        order.startAsNoCooking();
        order.addItem(OrderItemEntity.seatFee(3000, 2));
        return orderRepository.save(order);
    }

    /** 메뉴 항목이 든 주문 — pending이면 승인대기, 아니면 접수 */
    private OrderEntity menuOrder(BoothEntity owner, Long sessionId, boolean pending) {
        orderSeq++;
        OrderEntity order = new OrderEntity(owner.getId(), sessionId, "X" + orderSeq, LocalDate.of(2020, 1, 1),
                orderSeq, "idem-pos-" + orderSeq, 8000, false, now());
        if (pending) {
            order.startPendingApproval();
        }
        order.addItem(new OrderItemEntity(1L, "김치전", 8000, 1));
        return orderRepository.save(order);
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
        // 프론트가 이미 받는 unpaidWarning은 유지하고 명세 O6의 id·label·status를 더한다. warning은 미결제 없으면 필드째 없다.
        // rejectedPendingCount(자동 거절한 승인대기 수)는 completedOrderCount처럼 0이어도 싣는다
        assertEquals(Set.of("unpaidWarning", "id", "label", "status", "completedOrderCount", "rejectedPendingCount",
                        "waivedSeatFeeCount"),
                fieldNames(objectMapper.readTree(body)));

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

    /**
     * 퇴실하면 그 손님의 남은 승인대기(O28) 주문은 자동 거절(CANCELED)된다 — 승인대기는 UnpaidOrderRule에서 빠져
     * completedOrderCount(RECEIVED만 대상)로도 안 잡히므로, 그대로 두면 손님은 떠났는데 "승인 대기" 탭에 영영 남는다.
     * 다른 부스의 승인대기는 건드리지 않는다.
     *
     * <p>앞 손님(이미 종료된 세션)의 승인대기가 남는 단언은 그대로 둔다 — 새 규칙(M2)에서는 세션을 끝내는 두 경로(O6 퇴실·C1 유휴 재스캔)가
     * 모두 그 자리에서 승인대기를 거절하므로 정상 흐름으로는 "종료된 세션의 승인대기"가 생기지 않는다. 여기 시딩은 새 규칙 이전에
     * 남은 데이터(또는 직접 넣은 행)를 흉내 낸 것이고, 이 단언이 지키는 건 "퇴실의 거절 범위는 이번에 종료한 세션뿐"이라는 범위 규칙이다 —
     * 퇴실이 테이블의 과거 세션까지 훑어 거절하면 이미 끝난 손님의 기록(사유·취소자)을 뒤늦게 덮어쓴다. 유휴 재스캔 쪽 새 규칙은
     * c1IdleRescanRejectsPendingApprovalOfTheExpiredSession이 본다
     */
    @Test
    void o6AutoRejectsPendingApprovalOrdersOfTheEndingSessionOnly() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity past = endedSession(table, "sess-past");
        OrderEntity pastPending = pendingApprovalOrder(booth, past.getId());
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        OrderEntity pending = pendingApprovalOrder(booth, session.getId());
        TableEntity foreign = table(otherBooth, "B-1", true);
        TableSessionEntity foreignSession = openSession(foreign, "sess-b1", now().minusMinutes(30), now().minusMinutes(1));
        OrderEntity foreignPending = pendingApprovalOrder(otherBooth, foreignSession.getId());

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaidWarning").value(false))       // 승인대기는 미결제 정의에서 빠진다
                .andExpect(jsonPath("$.warning").doesNotExist())
                .andExpect(jsonPath("$.completedOrderCount").value(0))     // RECEIVED가 아니라 자동완료 대상도 아니다
                .andExpect(jsonPath("$.rejectedPendingCount").value(1));   // 이번 세션 것만 — 앞 손님·다른 부스는 세지 않는다

        OrderEntity reloadedPending = orderRepository.findById(pending.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELED, reloadedPending.getStatus());
        assertEquals("테이블 퇴실로 자동 거절", reloadedPending.getCancelReason());
        assertEquals("SYSTEM", reloadedPending.getCanceledBy());
        assertEquals(OrderStatus.PENDING_APPROVAL, orderRepository.findById(pastPending.getId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.PENDING_APPROVAL, orderRepository.findById(foreignPending.getId()).orElseThrow().getStatus());
    }

    /**
     * H1 — 입금 확인(O11)은 승인대기도 PAID로 만들 수 있다(#117 승인대기 카드 "결제 확인"). 퇴실의 자동 거절이 주문 축만 CANCELED로
     * 바꾸면 CANCELED+PAID가 되어 환불 목록(REFUND_NEEDED)에서 사라지고 매출(PAID)에는 남는다. O13과 같이 REFUND_NEEDED로 넘어가야 한다.
     * 미입금 승인대기는 UNPAID 그대로(받은 돈이 없으니 환불 대상이 아니다). "테이블 비우기"는 거절 건수를 응답에 싣는다
     */
    @Test
    void o6VacateRejectsPaidPendingApprovalAsRefundNeededAndReportsCount() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        OrderEntity paidPending = pendingApprovalOrder(booth, session.getId());
        assertEquals(1, orderRepository.markPaid(paidPending.getId(), booth.getId(), PaymentMethod.BANK_TRANSFER, "admin", now()));
        OrderEntity unpaidPending = pendingApprovalOrder(booth, session.getId());

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.rejectedPendingCount").value(2));

        OrderEntity reloadedPaid = orderRepository.findById(paidPending.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELED, reloadedPaid.getStatus());
        assertEquals(PaymentStatus.REFUND_NEEDED, reloadedPaid.getPaymentStatus());   // 받은 돈 → 환불 대상
        assertEquals("테이블 퇴실로 자동 거절", reloadedPaid.getCancelReason());
        assertEquals("SYSTEM", reloadedPaid.getCanceledBy());
        assertNotNull(reloadedPaid.getCanceledAt());
        assertEquals("admin", reloadedPaid.getApprovedBy());                          // 입금 기록은 그대로 남는다
        OrderEntity reloadedUnpaid = orderRepository.findById(unpaidPending.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELED, reloadedUnpaid.getStatus());
        assertEquals(PaymentStatus.UNPAID, reloadedUnpaid.getPaymentStatus());
    }

    /**
     * 주문했다가 "안 먹겠다"로 비우기 — 메뉴(승인대기)는 자동 거절되고, 메뉴가 하나도 안 남았으니 미입금 자릿세도 면제된다.
     * 예전엔 자릿세가 처음부터 DONE이라 거절·자동 완료 어디에도 안 걸려 완료 탭에 "자릿세·미결제"로 영영 남았다
     */
    @Test
    void o6VacateWaivesUnpaidSeatFeeWhenNoMenuOrderRemains() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        OrderEntity seatFee = seatFeeOrder(booth, session.getId());
        OrderEntity pendingMenu = menuOrder(booth, session.getId(), true);

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectedPendingCount").value(1))
                .andExpect(jsonPath("$.waivedSeatFeeCount").value(1))
                .andExpect(jsonPath("$.unpaidWarning").value(false))     // 면제한 자릿세는 미결제 경고에서 빠진다
                .andExpect(jsonPath("$.warning").doesNotExist());

        assertEquals(OrderStatus.CANCELED, orderRepository.findById(pendingMenu.getId()).orElseThrow().getStatus());
        OrderEntity waived = orderRepository.findById(seatFee.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELED, waived.getStatus());
        assertEquals(PaymentStatus.UNPAID, waived.getPaymentStatus());
        assertEquals("주문 없이 퇴실해 자릿세 면제", waived.getCancelReason());
        assertEquals("SYSTEM", waived.getCanceledBy());
    }

    /** 음식을 먹었으면(살아 있는 메뉴 주문이 있으면) 자릿세는 그대로 미결제로 남는다 — 비우기는 받을 돈을 없애지 않는다 */
    @Test
    void o6VacateKeepsSeatFeeWhenAMenuOrderRemains() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        OrderEntity seatFee = seatFeeOrder(booth, session.getId());
        menuOrder(booth, session.getId(), false);          // 접수(승인됨) — 먹은 주문
        menuOrder(booth, session.getId(), true);           // 추가 주문은 승인대기 — 거절돼도 위 주문이 남는다

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectedPendingCount").value(1))
                .andExpect(jsonPath("$.waivedSeatFeeCount").value(0))
                .andExpect(jsonPath("$.warning").value("미결제 주문 2건 있음"));

        OrderEntity kept = orderRepository.findById(seatFee.getId()).orElseThrow();
        assertEquals(OrderStatus.DONE, kept.getStatus());
        assertEquals(PaymentStatus.UNPAID, kept.getPaymentStatus());
    }

    /** 이미 입금된 자릿세는 면제하지 않는다 — 돌려줄지는 운영자가 O13으로 정한다 */
    @Test
    void o6VacateDoesNotTouchPaidSeatFee() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        OrderEntity seatFee = seatFeeOrder(booth, session.getId());
        assertEquals(1, orderRepository.markPaid(seatFee.getId(), booth.getId(), PaymentMethod.BANK_TRANSFER, "admin", now()));
        menuOrder(booth, session.getId(), true);

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waivedSeatFeeCount").value(0));

        OrderEntity paid = orderRepository.findById(seatFee.getId()).orElseThrow();
        assertEquals(OrderStatus.DONE, paid.getStatus());
        assertEquals(PaymentStatus.PAID, paid.getPaymentStatus());
    }

    /** 승인대기가 없으면 rejectedPendingCount는 0으로 실린다(필드가 빠지지 않는다 — 프론트가 숫자로 읽는다) */
    @Test
    void o6VacateReportsZeroRejectedPendingCountWhenNothingPending() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectedPendingCount").value(0));
    }

    /**
     * M2 — C1 유휴 재스캔이 옛 세션을 종료할 때 그 승인대기도 퇴실과 같은 규칙으로 거절한다(입금된 건은 REFUND_NEEDED).
     * 예전에는 세션만 끝내서 영업일이 바뀔 때까지 "승인 대기" 탭에 떠난 손님 주문이 매달렸다(토큰은 410, 승인도 종료 세션이라 409).
     * 이미 승인된 접수 주문은 그대로 두고, 새 세션·다른 테이블의 승인대기는 건드리지 않는다
     */
    @Test
    void c1IdleRescanRejectsPendingApprovalOfTheExpiredSession() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity idle = openSession(table, "sess-idle", now().minusHours(6), now().minusHours(5));
        OrderEntity pending = pendingApprovalOrder(booth, idle.getId());
        OrderEntity paidPending = pendingApprovalOrder(booth, idle.getId());
        assertEquals(1, orderRepository.markPaid(paidPending.getId(), booth.getId(), PaymentMethod.BANK_TRANSFER, "admin", now()));
        OrderEntity received = unpaidOrder(booth, idle.getId());   // 영업일이 먼 과거라 유휴 예외를 만들지 않는다
        TableEntity other = table(booth, "A-2", true);
        TableSessionEntity otherSession = openSession(other, "sess-other", now().minusMinutes(30), now().minusMinutes(1));
        OrderEntity otherPending = pendingApprovalOrder(booth, otherSession.getId());

        String newToken = scan(table.getTableToken(), false);
        assertNotEquals("sess-idle", newToken);
        assertNotNull(reloadSession(idle.getId()).getEndedAt());

        OrderEntity reloadedPending = orderRepository.findById(pending.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELED, reloadedPending.getStatus());
        assertEquals(PaymentStatus.UNPAID, reloadedPending.getPaymentStatus());
        assertEquals("유휴 만료 재스캔으로 자동 거절", reloadedPending.getCancelReason());
        assertEquals("SYSTEM", reloadedPending.getCanceledBy());
        OrderEntity reloadedPaid = orderRepository.findById(paidPending.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELED, reloadedPaid.getStatus());
        assertEquals(PaymentStatus.REFUND_NEEDED, reloadedPaid.getPaymentStatus());
        assertEquals(OrderStatus.RECEIVED, orderRepository.findById(received.getId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.PENDING_APPROVAL, orderRepository.findById(otherPending.getId()).orElseThrow().getStatus());
    }

    /**
     * M1 — 이미 종료된 세션(퇴실·유휴 재스캔)의 승인대기는 승인할 수 없다(409 INVALID_STATE). 승인하면 떠난 손님 주문이 주방 대기열에
     * 들어가고, 퇴실이 끝난 뒤라 "결제 완료"의 미결제 판정도 받지 않은 미수금이 된다. 주문은 승인대기 그대로 남는다
     */
    @Test
    void o28ApproveOnEndedSessionIsConflictAndChangesNothing() throws Exception {
        TableEntity table = table(booth, "A-1", false);
        TableSessionEntity ended = endedSession(table, "sess-ended");
        OrderEntity pending = pendingApprovalOrder(booth, ended.getId());

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/approve", pending.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertEquals(OrderStatus.PENDING_APPROVAL, orderRepository.findById(pending.getId()).orElseThrow().getStatus());
    }

    /** 종료된 세션 판정이 존재 은닉을 깨지 않는다 — 다른 부스의 종료 세션 주문은 409가 아니라 404 */
    @Test
    void o28ApproveOnOtherBoothsEndedSessionIsNotFound() throws Exception {
        TableEntity foreign = table(otherBooth, "B-1", false);
        TableSessionEntity ended = endedSession(foreign, "sess-foreign-ended");
        OrderEntity pending = pendingApprovalOrder(otherBooth, ended.getId());

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/approve", pending.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isNotFound());
    }

    /** 열린 세션의 승인대기는 그대로 승인된다 — 세션 잠금이 정상 경로를 막지 않는다 */
    @Test
    void o28ApproveOnOpenSessionStillWorks() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        OrderEntity pending = pendingApprovalOrder(booth, session.getId());

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/approve", pending.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
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

    /**
     * H2 — 승인대기(O28)가 남은 "결제 완료"는 409 CHECKOUT_PENDING_APPROVAL로 전부 롤백한다. 승인대기는 미결제 정의에서 빠져
     * CHECKOUT_UNPAID_REMAINS로는 안 잡히는데, 손님 결제 안내 화면은 승인대기 금액까지 더한 총액을 이체하라고 했다 — 조용히 자동 거절하면
     * 받은 돈과 확정 주문이 어긋난다. 세션·테이블·주문(입금된 승인대기 포함) 어느 것도 바뀌지 않는다
     */
    @Test
    void o6RequireSettledRejectsWhenPendingApprovalRemainsAndChangesNothing() throws Exception {
        TableEntity table = table(booth, "A-1", true);
        TableSessionEntity session = openSession(table, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        paidOrder(booth, session.getId());                                   // O24가 입금 확인한 접수 주문
        OrderEntity pending = pendingApprovalOrder(booth, session.getId());   // 아직 승인 안 된 주문
        OrderEntity paidPending = pendingApprovalOrder(booth, session.getId());
        assertEquals(1, orderRepository.markPaid(paidPending.getId(), booth.getId(), PaymentMethod.BANK_TRANSFER, "admin", now()));

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", table.getId())
                        .param("requireSettled", "true")
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHECKOUT_PENDING_APPROVAL"))
                .andExpect(jsonPath("$.error.details.pendingOrderCount").value(2));

        assertNull(reloadSession(session.getId()).getEndedAt());
        assertEquals(0, reloadSession(session.getId()).getEndedAtKey());
        assertEquals(TableStatus.OCCUPIED, reloadTable(table.getId()).getStatus());
        OrderEntity reloadedPending = orderRepository.findById(pending.getId()).orElseThrow();
        assertEquals(OrderStatus.PENDING_APPROVAL, reloadedPending.getStatus());
        assertNull(reloadedPending.getCancelReason());
        OrderEntity reloadedPaid = orderRepository.findById(paidPending.getId()).orElseThrow();
        assertEquals(OrderStatus.PENDING_APPROVAL, reloadedPaid.getStatus());
        assertEquals(PaymentStatus.PAID, reloadedPaid.getPaymentStatus());
        assertEquals(0, orderRepository.findAll().stream().filter(o -> o.getStatus() == OrderStatus.DONE).count());
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

    // ── 자리 이동(명세서 밖) ─────────────────────────────

    private org.springframework.test.web.servlet.ResultActions move(Long fromTableId, Long toTableId) throws Exception {
        Long sessionId = tableSessionRepository.findOpenByTableId(fromTableId).map(TableSessionEntity::getId).orElse(-1L);
        return moveWithSession(fromTableId, toTableId, sessionId);
    }

    private org.springframework.test.web.servlet.ResultActions moveWithSession(Long fromTableId, Long toTableId,
                                                                              Long sessionId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/tables/{tableId}/move", fromTableId)
                .header("Authorization", "Bearer " + login("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toTableId\":" + toTableId + ",\"sessionId\":" + sessionId + "}"));
    }

    @Test
    void moveRequiresSessionIdAndChangesNothing() throws Exception {
        TableEntity a = table(booth, "A-1", false);
        TableEntity b = table(booth, "B-1", false);
        scan(a.getTableToken(), false);
        Long sessionId = openSessionIdOf(a);
        TableSessionEntity before = reloadSession(sessionId);
        OrderEntity order = menuOrder(booth, sessionId, false);

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/move", a.getId())
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toTableId\":" + b.getId() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        moveWithSession(a.getId(), b.getId(), null).andExpect(status().isBadRequest());

        assertEquals(a.getId(), reloadSession(sessionId).getTable().getId());
        assertEquals(before.getLastActivityAt(), reloadSession(sessionId).getLastActivityAt());
        assertNull(reloadSession(sessionId).getEndedAt());
        assertEquals(TableStatus.OCCUPIED, reloadTable(a.getId()).getStatus());
        assertEquals(TableStatus.EMPTY, reloadTable(b.getId()).getStatus());
        assertEquals(sessionId, orderRepository.findById(order.getId()).orElseThrow().getSessionId());
        assertEquals(order.getTableLabel(), orderRepository.findById(order.getId()).orElseThrow().getTableLabel());
    }

    @Test
    void moveRejectsReplacedSessionAndLeavesNewGuestsAndOrdersUntouched() throws Exception {
        TableEntity a = table(booth, "A-1", false);
        TableEntity b = table(booth, "B-1", false);
        scan(a.getTableToken(), false);
        Long oldSessionId = openSessionIdOf(a);
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", a.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk());
        scan(a.getTableToken(), false);
        Long newSessionId = openSessionIdOf(a);
        assertNotEquals(oldSessionId, newSessionId);
        OrderEntity order = menuOrder(booth, newSessionId, false);
        TableSessionEntity before = reloadSession(newSessionId);
        long sessionCount = tableSessionRepository.count();
        long orderCount = orderRepository.count();

        moveWithSession(a.getId(), b.getId(), oldSessionId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertEquals(TableStatus.OCCUPIED, reloadTable(a.getId()).getStatus());
        assertEquals(TableStatus.EMPTY, reloadTable(b.getId()).getStatus());
        assertEquals(newSessionId, openSessionIdOf(a));
        assertTrue(tableSessionRepository.findOpenByTableId(b.getId()).isEmpty());
        assertEquals(a.getId(), reloadSession(newSessionId).getTable().getId());
        assertEquals(before.getLastActivityAt(), reloadSession(newSessionId).getLastActivityAt());
        assertEquals(before.getPartySize(), reloadSession(newSessionId).getPartySize());
        assertNull(reloadSession(newSessionId).getEndedAt());
        assertNotNull(reloadSession(oldSessionId).getEndedAt());
        OrderEntity unchanged = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(newSessionId, unchanged.getSessionId());
        assertEquals(order.getTableLabel(), unchanged.getTableLabel());
        assertEquals(order.getStatus(), unchanged.getStatus());
        assertEquals(order.getPaymentStatus(), unchanged.getPaymentStatus());
        assertEquals(order.getTotalAmount(), unchanged.getTotalAmount());
        assertEquals(sessionCount, tableSessionRepository.count());
        assertEquals(orderCount, orderRepository.count());
    }

    /**
     * 주문이 있는 A 손님을 빈 B로 옮긴다 — 세션·토큰이 그대로라 손님 폰은 계속 되고 주문·미결제가 B 결제 모달로 따라간다.
     * 이동 뒤 B QR은 같은 세션을 복원하고, A QR은 새 손님(새 세션)이다
     */
    @Test
    void moveCarriesTheSessionWithItsOrdersToTheEmptyTable() throws Exception {
        TableEntity a = table(booth, "A-1", false);
        TableEntity b = table(booth, "B-1", false);
        String token = scan(a.getTableToken(), false);
        Long sessionId = tableSessionRepository.findOpenByTableId(a.getId()).orElseThrow().getId();
        OrderEntity seatFee = seatFeeOrder(booth, sessionId);
        OrderEntity menu = menuOrder(booth, sessionId, false);

        move(a.getId(), b.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.fromLabel").value("A-1"))
                .andExpect(jsonPath("$.toLabel").value("B-1"));

        assertEquals(b.getId(), tableSessionRepository.findById(sessionId).orElseThrow().getTable().getId());
        assertEquals(TableStatus.EMPTY, reloadTable(a.getId()).getStatus());
        assertEquals(TableStatus.OCCUPIED, reloadTable(b.getId()).getStatus());
        // 손님 폰(같은 토큰)은 끊기지 않는다
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2));
        // 결제 모달(B 기준)에 주문·자릿세가 따라온다
        mockMvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + login("admin"))
                        .param("tableId", b.getId().toString()).param("activeSessionOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2));
        // B QR은 같은 세션 복원, A QR은 새 세션
        assertEquals(token, scan(b.getTableToken(), true));
        assertNotEquals(token, scan(a.getTableToken(), false));
        assertEquals(OrderStatus.DONE, orderRepository.findById(seatFee.getId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.RECEIVED, orderRepository.findById(menu.getId()).orElseThrow().getStatus());
    }

    /**
     * 진행 중 주문(승인대기·접수)은 주문현황 카드가 새 자리를 가리키도록 테이블 라벨이 B로 바뀐다 — 주문번호는 입금 대조용이라 그대로.
     * 완료 주문(자릿세)은 옛 자리 기록으로 남는다
     */
    @Test
    void moveRelabelsOnlyInProgressOrdersToTheNewTable() throws Exception {
        TableEntity a = table(booth, "A-1", false);
        TableEntity b = table(booth, "B-1", false);
        scan(a.getTableToken(), false);
        Long sessionId = tableSessionRepository.findOpenByTableId(a.getId()).orElseThrow().getId();
        OrderEntity done = seatFeeOrder(booth, sessionId);
        OrderEntity received = menuOrder(booth, sessionId, false);
        OrderEntity pending = menuOrder(booth, sessionId, true);

        move(a.getId(), b.getId()).andExpect(status().isOk());

        assertEquals("B-1", orderRepository.findById(received.getId()).orElseThrow().getTableLabel());
        assertEquals("B-1", orderRepository.findById(pending.getId()).orElseThrow().getTableLabel());
        assertEquals(received.getOrderNo(), orderRepository.findById(received.getId()).orElseThrow().getOrderNo());
        assertNull(orderRepository.findById(done.getId()).orElseThrow().getTableLabel());   // 완료 주문은 건드리지 않는다
    }

    /**
     * 이동과 겹친 주문 — 손님 요청이 이동 전에 인증을 마쳐 옛 라벨(A-1)을 들고 저장에 도착해도, 저장은 세션 행을 잠근 뒤
     * 세션의 현재 테이블(B-1)로 주문번호·테이블 스냅샷을 정한다. 안 그러면 이동 직후 들어간 주문 하나만 A로 찍혀 옛 자리로 나간다
     */
    @Test
    void orderSavedAfterMoveUsesTheNewTableEvenWithAStaleLabel() throws Exception {
        TableEntity a = table(booth, "A-1", false);
        TableEntity b = table(booth, "B-1", false);
        scan(a.getTableToken(), false);
        Long sessionId = tableSessionRepository.findOpenByTableId(a.getId()).orElseThrow().getId();
        move(a.getId(), b.getId()).andExpect(status().isOk());

        OrderEntity saved = orderWriter.save(new OrderWriter.OrderSpec(
                booth.getId(), sessionId, "A1", "A-1", "idem-stale-label", 8000,
                java.util.List.of(new OrderItemEntity(1L, "김치전", 8000, 1)), now(), false));

        assertEquals("B-1", saved.getTableLabel());
        assertTrue(saved.getOrderNo().startsWith("B1-"), saved.getOrderNo());
    }

    /** 옮길 자리는 빈 테이블만 — 열린 세션이 있으면(주문 없어도) 409이고 아무것도 바뀌지 않는다. 합석은 하지 않는다 */
    @Test
    void moveRejectsATargetThatHasAnOpenSession() throws Exception {
        TableEntity a = table(booth, "A-1", true);
        TableEntity b = table(booth, "B-1", true);
        TableSessionEntity sa = openSession(a, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        TableSessionEntity sb = openSession(b, "sess-b1", now().minusMinutes(30), now().minusMinutes(1));

        move(a.getId(), b.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertEquals(a.getId(), tableSessionRepository.findById(sa.getId()).orElseThrow().getTable().getId());
        assertEquals(b.getId(), tableSessionRepository.findById(sb.getId()).orElseThrow().getTable().getId());
        assertEquals(TableStatus.OCCUPIED, reloadTable(a.getId()).getStatus());
    }

    /**
     * 유휴 만료(정리 필요 — 손님이 떠난 자리) 세션은 옮기지 않는다 — 옮기면 활동 시각이 갱신돼 떠난 손님 세션이 B에서 되살아나,
     * B에 앉은 새 손님이 앞 손님 주문을 복원받는다. 아무것도 바뀌지 않는다
     */
    @Test
    void moveRejectsAnIdleExpiredSession() throws Exception {
        TableEntity a = table(booth, "A-1", true);
        TableEntity b = table(booth, "B-1", false);
        TableSessionEntity idle = openSession(a, "sess-idle", now().minusDays(1), now().minusDays(1));

        move(a.getId(), b.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertEquals(a.getId(), tableSessionRepository.findById(idle.getId()).orElseThrow().getTable().getId());
        assertEquals(TableStatus.EMPTY, reloadTable(b.getId()).getStatus());
    }

    @Test
    void moveRejectsInvalidRequests() throws Exception {
        TableEntity a = table(booth, "A-1", false);
        TableEntity b = table(booth, "B-1", false);
        TableEntity foreign = table(otherBooth, "C-1", false);

        move(a.getId(), b.getId()).andExpect(status().isConflict());            // A에 옮길 손님이 없다
        openSession(a, "sess-a1", now().minusMinutes(30), now().minusMinutes(1));
        move(a.getId(), a.getId()).andExpect(status().isBadRequest());          // 같은 테이블
        move(a.getId(), null).andExpect(status().isBadRequest());               // 대상 없음
        move(a.getId(), foreign.getId()).andExpect(status().isNotFound());      // 남의 부스 테이블
        move(a.getId(), 999_999L).andExpect(status().isNotFound());             // 없는 테이블
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/move", a.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"toTableId\":" + b.getId() + "}"))
                .andExpect(status().isUnauthorized());                          // 로그인 없음
    }

    // ── 자리 합석(명세서 밖) ─────────────────────────────

    /** 운영자 화면처럼 두 테이블의 지금 열린 세션 id를 함께 보낸다(없으면 -1) */
    private org.springframework.test.web.servlet.ResultActions merge(TableEntity from, TableEntity to) throws Exception {
        return mergeWith(from.getId(), to.getId(), openSessionIdOrNone(from), openSessionIdOrNone(to));
    }

    private org.springframework.test.web.servlet.ResultActions mergeWith(Long fromTableId, Long toTableId,
                                                                         Long fromSessionId, Long toSessionId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/tables/{tableId}/merge", fromTableId)
                .header("Authorization", "Bearer " + login("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toTableId\":" + toTableId + ",\"fromSessionId\":" + fromSessionId
                        + ",\"toSessionId\":" + toSessionId + "}"));
    }

    private Long openSessionIdOf(TableEntity table) {
        return tableSessionRepository.findOpenByTableId(table.getId()).orElseThrow().getId();
    }

    private Long openSessionIdOrNone(TableEntity table) {
        return tableSessionRepository.findOpenByTableId(table.getId()).map(TableSessionEntity::getId).orElse(-1L);
    }

    /**
     * A 손님을 B 손님과 합친다 — A의 주문·호출이 B 세션으로 옮겨가고(계산서 하나) 인원이 더해지며, A 세션은 끝나고 A는 빈 테이블이 된다.
     * A 일행 폰(옛 토큰)은 퇴실 때처럼 끊기고, B QR을 찍으면 합친 계산서로 이어진다. A QR은 새 손님
     */
    @Test
    void mergeCombinesOrdersCallsAndPartyIntoTheTargetSession() throws Exception {
        TableEntity a = table(booth, "A-1", false);
        TableEntity b = table(booth, "B-1", false);
        String tokenA = scan(a.getTableToken(), false);
        String tokenB = scan(b.getTableToken(), false);
        Long sessionA = openSessionIdOf(a);
        Long sessionB = openSessionIdOf(b);
        tableSessionRepository.updatePartySizeIfActive(sessionA, 2);
        tableSessionRepository.updatePartySizeIfActive(sessionB, 3);
        seatFeeOrder(booth, sessionA);
        OrderEntity aMenu = menuOrder(booth, sessionA, false);
        seatFeeOrder(booth, sessionB);
        menuOrder(booth, sessionB, false);
        StaffCallEntity call = staffCallRepository.save(new StaffCallEntity(reloadSession(sessionA), CallReason.HELP, now()));

        merge(a, b)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionB))
                .andExpect(jsonPath("$.partySize").value(5))
                .andExpect(jsonPath("$.warnings.length()").value(0));   // 양쪽 자릿세 처리됨

        OrderEntity movedMenu = orderRepository.findById(aMenu.getId()).orElseThrow();
        assertEquals(sessionB, movedMenu.getSessionId());
        assertEquals("B-1", movedMenu.getTableLabel());                         // 진행 중 주문 카드도 B로
        assertEquals(aMenu.getOrderNo(), movedMenu.getOrderNo());               // 주문번호는 입금 대조용이라 그대로
        assertEquals(sessionB, staffCallRepository.findById(call.getId()).orElseThrow().getSession().getId());
        assertNotNull(reloadSession(sessionA).getEndedAt());
        assertEquals(TableStatus.EMPTY, reloadTable(a.getId()).getStatus());
        assertEquals(TableStatus.OCCUPIED, reloadTable(b.getId()).getStatus());

        // A 일행 폰(옛 토큰)은 퇴실 때처럼 끊긴다
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", tokenA))
                .andExpect(status().isGone());
        // B QR을 찍으면 남은 세션(합친 계산서, 주문 4건)으로 이어진다 — A 일행이 다시 찍어도 같은 세션
        assertEquals(tokenB, scan(b.getTableToken(), true));
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(4));
        // A QR은 새 손님
        assertNotEquals(tokenA, scan(a.getTableToken(), false));
    }

    /** 한쪽 일행만 자릿세가 처리된 채 합치면 나머지 일행 몫은 자동으로 붙지 않는다 — 운영자에게 추가 자릿세를 넣으라고 알린다 */
    @Test
    void mergeWarnsWhenOnlyOnePartysSeatFeeWasCharged() throws Exception {
        TableEntity a = table(booth, "A-1", false);
        TableEntity b = table(booth, "B-1", false);
        scan(a.getTableToken(), false);
        scan(b.getTableToken(), false);
        Long sessionA = openSessionIdOf(a);
        tableSessionRepository.updatePartySizeIfActive(sessionA, 2);
        tableSessionRepository.updatePartySizeIfActive(openSessionIdOf(b), 3);
        seatFeeOrder(booth, sessionA);
        menuOrder(booth, sessionA, false);   // B는 인원만 고르고 아직 주문 전

        merge(a, b)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("B-1 일행(3명)")));
    }

    /** 인원을 아직 안 고른 일행을 합치면 그 일행 자릿세는 인원을 몰라 빠진다 — 운영자에게 알린다 */
    @Test
    void mergeWarnsWhenAPartysSizeIsUnknown() throws Exception {
        TableEntity a = table(booth, "A-1", false);
        TableEntity b = table(booth, "B-1", false);
        scan(a.getTableToken(), false);   // A는 QR만 찍고 인원 선택 전
        scan(b.getTableToken(), false);
        Long sessionB = openSessionIdOf(b);
        tableSessionRepository.updatePartySizeIfActive(sessionB, 3);
        seatFeeOrder(booth, sessionB);
        menuOrder(booth, sessionB, false);

        merge(a, b)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partySize").value(3))
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("A-1 일행(인원 미선택)")));
    }

    /**
     * 운영자 화면이 본 세션과 지금 세션이 다르면(그 사이 퇴실하고 새 손님이 앉음) 합치지 않는다 — 되돌릴 수 없어 모르는 일행끼리 합쳐지면 안 된다
     */
    @Test
    void mergeRejectsStaleSessionIds() throws Exception {
        TableEntity a = table(booth, "A-1", false);
        TableEntity b = table(booth, "B-1", false);
        scan(a.getTableToken(), false);
        scan(b.getTableToken(), false);
        Long sessionA = openSessionIdOf(a);
        Long sessionB = openSessionIdOf(b);

        mergeWith(a.getId(), b.getId(), sessionA + 999, sessionB)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        mergeWith(a.getId(), b.getId(), sessionA, sessionB + 999)
                .andExpect(status().isConflict());

        assertNull(reloadSession(sessionA).getEndedAt());                       // 아무것도 바뀌지 않는다
        assertEquals(TableStatus.OCCUPIED, reloadTable(a.getId()).getStatus());
    }

    @Test
    void mergeRejectsInvalidRequests() throws Exception {
        TableEntity a = table(booth, "A-1", true);
        TableEntity b = table(booth, "B-1", false);
        TableEntity foreign = table(otherBooth, "C-1", false);
        Long sessionA = openSession(a, "sess-a1", now().minusMinutes(30), now().minusMinutes(1)).getId();

        merge(a, b).andExpect(status().isConflict());                            // B에 손님이 없다 — 자리 이동을 쓴다
        merge(b, a).andExpect(status().isConflict());                            // 합칠 손님이 없다
        merge(a, a).andExpect(status().isBadRequest());                          // 같은 테이블
        merge(a, foreign).andExpect(status().isNotFound());                      // 남의 부스 테이블
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/merge", a.getId())
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"toTableId\":" + b.getId() + "}"))
                .andExpect(status().isBadRequest());                             // 세션 id 없음
        // 손님이 떠난 것으로 보이는 B(유휴 만료)와는 합치지 않는다 — 떠난 손님 주문이 남의 계산서에 섞인다
        TableSessionEntity idle = openSession(b, "sess-idle", now().minusDays(1), now().minusDays(1));
        mergeWith(a.getId(), b.getId(), sessionA, idle.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertNull(reloadSession(idle.getId()).getEndedAt());                  // 아무것도 바뀌지 않는다
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
