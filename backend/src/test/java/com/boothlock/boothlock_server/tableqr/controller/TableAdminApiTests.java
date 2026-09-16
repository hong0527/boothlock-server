package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
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
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TableAdminApiTests {

    /** 프로덕션 코드가 KST로 시각을 만든다 — 시딩도 같은 기준이어야 build.gradle의 시간대 고정에 기대지 않는다 */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired OrderRepository orderRepository;

    private BoothEntity booth;
    private TableEntity table;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffRepository.deleteAll();
        boothRepository.deleteAll();

        booth = boothRepository.save(new BoothEntity("QR 부스", "은행 1234", null));
        table = tableRepository.save(new TableEntity(booth, "A-1", "old-token"));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staffRepository.save(new StaffAccountEntity(booth, "admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void regeneratesTokenAndKeepsActiveSession() throws Exception {
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, "session-token-1", LocalDateTime.now(KST)));

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/regenerate-token", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(table.getId()))
                .andExpect(jsonPath("$.label").value("A-1"))
                .andExpect(jsonPath("$.qrUrl").value("/api/v1/admin/tables/" + table.getId() + "/qr"));

        TableEntity reloaded = tableRepository.findById(table.getId()).orElseThrow();
        assertNotEquals("old-token", reloaded.getTableToken());

        TableSessionEntity reloadedSession = tableSessionRepository.findById(session.getId()).orElseThrow();
        assertNull(reloadedSession.getEndedAt());
    }

    /** C1(세션 발급)은 아직 이 브랜치에 없어 API로는 못 확인한다 — 저장소 조회로 옛 토큰이 더는 안 풀리는 것만 확인 */
    @Test
    void oldTokenNoLongerResolvesAfterRegenerate() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/regenerate-token", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk());

        assertTrue(tableRepository.findByTableToken("old-token").isEmpty());
    }

    @Test
    void rejectsUnknownTableIdWithNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/regenerate-token", 999_999L)
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void hidesOtherBoothsTableAsNotFound() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staffRepository.save(new StaffAccountEntity(otherBooth, "other-admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));

        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/regenerate-token", table.getId())
                        .header("Authorization", "Bearer " + login("other-admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertEquals("old-token", tableRepository.findById(table.getId()).orElseThrow().getTableToken());
    }

    @Test
    void rejectsMissingAuthorizationWithUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/regenerate-token", table.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void bulkCreatesTablesFromCountAndPrefix() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/bulk")
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":3,\"labelPrefix\":\"B\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tables.length()").value(3))
                .andExpect(jsonPath("$.tables[0].label").value("B-1"))
                .andExpect(jsonPath("$.tables[0].qrUrl").exists())
                .andExpect(jsonPath("$.tables[1].label").value("B-2"))
                .andExpect(jsonPath("$.tables[2].label").value("B-3"));

        // 기존 A-1(setUp) + 신규 3건
        assertEquals(4, tableRepository.count());
        List<String> tokens = tableRepository.findByBoothId(booth.getId()).stream()
                .map(TableEntity::getTableToken)
                .distinct()
                .toList();
        assertEquals(4, tokens.size());
    }

    @Test
    void bulkCreatesTablesFromExplicitLabels() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/bulk")
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[\"C-1\",\"C-2\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tables.length()").value(2))
                .andExpect(jsonPath("$.tables[0].label").value("C-1"))
                .andExpect(jsonPath("$.tables[1].label").value("C-2"));
    }

    @Test
    void rejectsBulkCreateWhenNormalizedLabelDuplicatesExisting() throws Exception {
        // setUp의 "A-1"과 정규화 결과가 같다("A1")
        mockMvc.perform(post("/api/v1/admin/tables/bulk")
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[\"A1\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertEquals(1, tableRepository.count());
    }

    @Test
    void rejectsBulkCreateWhenBatchHasDuplicateNormalizedLabels() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/bulk")
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[\"D-1\",\"D1\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertEquals(1, tableRepository.count());
    }

    @Test
    void rejectsBulkCreateOverMaxCount() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/bulk")
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":301,\"labelPrefix\":\"E\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsBulkCreateWhenBothCountAndLabelsGiven() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/bulk")
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":2,\"labelPrefix\":\"F\",\"labels\":[\"G-1\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsBulkCreateWhenNeitherCountNorLabelsGiven() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/bulk")
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsBulkCreateWithLoneMLabel() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/bulk")
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[\"M\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void bulkCreateScopesNewTablesToAuthenticatedBooth() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staffRepository.save(new StaffAccountEntity(otherBooth, "other-admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));

        // 다른 부스에도 "A-1"과 같은 라벨을 등록할 수 있어야 한다 — 중복 검사는 부스 스코프
        mockMvc.perform(post("/api/v1/admin/tables/bulk")
                        .header("Authorization", "Bearer " + login("other-admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[\"A-1\"]}"))
                .andExpect(status().isCreated());

        assertEquals(1, tableRepository.findByBoothId(otherBooth.getId()).size());
        assertEquals(1, tableRepository.findByBoothId(booth.getId()).size());
    }

    @Test
    void rejectsBulkCreateMissingAuthorizationWithUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"labelPrefix\":\"H\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void listsTableStatusesSortedByLabelWithNeedsCleanupFlag() throws Exception {
        // setUp의 table("A-1")은 EMPTY
        TableEntity occupiedWithSession = new TableEntity(booth, "B-1", "token-b1");
        occupiedWithSession.occupy();
        occupiedWithSession = tableRepository.save(occupiedWithSession);
        tableSessionRepository.save(new TableSessionEntity(occupiedWithSession, "session-b1", LocalDateTime.now(KST)));

        TableEntity occupiedWithoutSession = new TableEntity(booth, "C-1", "token-c1");
        occupiedWithoutSession.occupy();
        tableRepository.save(occupiedWithoutSession);

        mockMvc.perform(get("/api/v1/admin/tables")
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.length()").value(3))
                .andExpect(jsonPath("$.tables[0].label").value("A-1"))
                .andExpect(jsonPath("$.tables[0].status").value("EMPTY"))
                .andExpect(jsonPath("$.tables[0].needsCleanup").value(false))
                .andExpect(jsonPath("$.tables[1].label").value("B-1"))
                .andExpect(jsonPath("$.tables[1].status").value("OCCUPIED"))
                .andExpect(jsonPath("$.tables[1].needsCleanup").value(false))
                .andExpect(jsonPath("$.tables[2].label").value("C-1"))
                .andExpect(jsonPath("$.tables[2].status").value("OCCUPIED"))
                .andExpect(jsonPath("$.tables[2].needsCleanup").value(true));
    }

    @Test
    void listsTableStatusesInNumericLabelOrderNotLexicographic() throws Exception {
        // setUp의 table("A-1") 외 두 자리 접미사 라벨을 등록해 사전순이 아닌 숫자순 정렬을 검증한다
        tableRepository.save(new TableEntity(booth, "A-10", "token-a10"));
        tableRepository.save(new TableEntity(booth, "A-2", "token-a2"));

        mockMvc.perform(get("/api/v1/admin/tables")
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.length()").value(3))
                .andExpect(jsonPath("$.tables[0].label").value("A-1"))
                .andExpect(jsonPath("$.tables[1].label").value("A-2"))
                .andExpect(jsonPath("$.tables[2].label").value("A-10"));
    }

    @Test
    void listsEmptyTablesWhenBoothHasNone() throws Exception {
        tableRepository.deleteAll();

        mockMvc.perform(get("/api/v1/admin/tables")
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.length()").value(0));
    }

    @Test
    void scopesTableStatusesToAuthenticatedBooth() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staffRepository.save(new StaffAccountEntity(otherBooth, "other-admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        tableRepository.save(new TableEntity(otherBooth, "Z-1", "token-z1"));

        mockMvc.perform(get("/api/v1/admin/tables")
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.length()").value(1))
                .andExpect(jsonPath("$.tables[0].label").value("A-1"));
    }

    @Test
    void rejectsTableStatusesMissingAuthorizationWithUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tables"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void tableStatusesIncludePosXPosYSessionAndUnpaidOrderCount() throws Exception {
        TableEntity occupied = new TableEntity(booth, "B-1", "token-b1");
        occupied.occupy();
        occupied.updatePosition(120, 240);
        occupied = tableRepository.save(occupied);
        // 세션은 최근 활동이어야 O3의 session에 실린다 — 유휴(기본 180분) 세션은 정책상 비활성이라 session이 null이다
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(occupied, "session-b1", LocalDateTime.now(KST).minusMinutes(30)));

        OrderEntity unpaid = new OrderEntity(
                booth.getId(), session.getId(), "B1-1", LocalDate.of(2026, 8, 22),
                1, "idem-unpaid", 8000, false, LocalDateTime.of(2026, 8, 22, 18, 0));
        unpaid.addItem(new OrderItemEntity(1L, "메뉴", 8000, 1));
        orderRepository.save(unpaid);

        OrderEntity paid = new OrderEntity(
                booth.getId(), session.getId(), "B1-2", LocalDate.of(2026, 8, 22),
                2, "idem-paid", 8000, false, LocalDateTime.of(2026, 8, 22, 18, 5));
        paid.addItem(new OrderItemEntity(1L, "메뉴", 8000, 1));
        paid = orderRepository.save(paid);
        orderRepository.flush();
        // unpaidOrderCount는 RECEIVED+UNPAID만 세므로, 이 주문은 결제 확인 처리해 집계에서 빠지게 한다
        orderRepository.markPaid(paid.getId(), booth.getId(), PaymentMethod.CASH, "tester",
                LocalDateTime.of(2026, 8, 22, 18, 6));

        mockMvc.perform(get("/api/v1/admin/tables")
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0].label").value("A-1"))
                .andExpect(jsonPath("$.tables[0].posX").doesNotExist())
                .andExpect(jsonPath("$.tables[0].session").doesNotExist())
                .andExpect(jsonPath("$.tables[0].unpaidOrderCount").value(0))
                .andExpect(jsonPath("$.tables[1].label").value("B-1"))
                .andExpect(jsonPath("$.tables[1].posX").value(120))
                .andExpect(jsonPath("$.tables[1].posY").value(240))
                .andExpect(jsonPath("$.tables[1].session.startedAt").exists())
                .andExpect(jsonPath("$.tables[1].unpaidOrderCount").value(1));
    }

    @Test
    void updatesTablePositionAndReturnsO3Shape() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", table.getId())
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"posX\":100,\"posY\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(table.getId()))
                .andExpect(jsonPath("$.label").value("A-1"))
                .andExpect(jsonPath("$.posX").value(100))
                .andExpect(jsonPath("$.posY").value(50));

        assertEquals(100, tableRepository.findById(table.getId()).orElseThrow().getPosX());
        assertEquals(50, tableRepository.findById(table.getId()).orElseThrow().getPosY());
    }

    @Test
    void rejectsPositionUpdateMissingFields() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", table.getId())
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"posX\":100}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsPositionUpdateWithNegativeCoordinates() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", table.getId())
                        .header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"posX\":-1,\"posY\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void hidesOtherBoothsTableFromPositionUpdateAsNotFound() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staffRepository.save(new StaffAccountEntity(otherBooth, "other-admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));

        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", table.getId())
                        .header("Authorization", "Bearer " + login("other-admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"posX\":1,\"posY\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsPositionUpdateMissingAuthorizationWithUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", table.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"posX\":1,\"posY\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void addsSingleTableWithAutoNumberedLabel() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables")
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("T-1"))
                .andExpect(jsonPath("$.qrUrl").exists());

        mockMvc.perform(post("/api/v1/admin/tables")
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("T-2"));
    }

    @Test
    void deletingUnusedLastTableHardDeletesRowAndRevivesNumber() throws Exception {
        String token = login("admin");
        String body = mockMvc.perform(post("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        Long newTableId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(delete("/api/v1/admin/tables/{tableId}", newTableId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 이용 이력이 없으면 완전 삭제 — 행 자체가 사라진다(QR도 함께 폐기)
        assertTrue(tableRepository.findById(newTableId).isEmpty());

        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.length()").value(1))   // setUp의 A-1만 남음
                .andExpect(jsonPath("$.tables[0].label").value("A-1"));

        // 번호가 반납돼 다음 추가 때 같은 번호(T-1)가 그대로 되살아난다
        mockMvc.perform(post("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("T-1"));
    }

    @Test
    void deletingLastTableWithHistoryOnlySoftDeletesAndKeepsNumberRetired() throws Exception {
        String token = login("admin");
        String body = mockMvc.perform(post("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        Long newTableId = objectMapper.readTree(body).get("id").asLong();

        TableEntity newTable = tableRepository.findById(newTableId).orElseThrow();
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(newTable, "session-history-1", LocalDateTime.now(KST)));
        session.end(LocalDateTime.now(KST));
        tableSessionRepository.save(session);

        mockMvc.perform(delete("/api/v1/admin/tables/{tableId}", newTableId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 이용 기록이 있으면 완전 삭제하지 않는다 — 과거 세션의 외래키 보호
        TableEntity reloaded = tableRepository.findById(newTableId).orElseThrow();
        assertTrue(!reloaded.isActive());

        // 번호는 반납되지 않는다 — 다음 추가는 T-2
        mockMvc.perform(post("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("T-2"));
    }

    @Test
    void rejectsDeletingNonLastTable() throws Exception {
        String token = login("admin");
        String firstBody = mockMvc.perform(post("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        Long firstId = objectMapper.readTree(firstBody).get("id").asLong();
        mockMvc.perform(post("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        // T-1, T-2가 있는 상태에서 마지막이 아닌 T-1을 지우려 하면 거부
        mockMvc.perform(delete("/api/v1/admin/tables/{tableId}", firstId).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertTrue(tableRepository.findById(firstId).orElseThrow().isActive());
    }

    @Test
    void deleteBlocksTableWithActiveSession() throws Exception {
        tableSessionRepository.save(new TableSessionEntity(table, "session-active-1", LocalDateTime.now(KST)));

        mockMvc.perform(delete("/api/v1/admin/tables/{tableId}", table.getId())
                        .header("Authorization", "Bearer " + login("admin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertTrue(tableRepository.findById(table.getId()).orElseThrow().isActive());
    }

    @Test
    void deleteHidesOtherBoothsTableAsNotFound() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staffRepository.save(new StaffAccountEntity(otherBooth, "other-admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));

        mockMvc.perform(delete("/api/v1/admin/tables/{tableId}", table.getId())
                        .header("Authorization", "Bearer " + login("other-admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsAddSingleTableMissingAuthorizationWithUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsDeleteMissingAuthorizationWithUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/tables/{tableId}", table.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
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
