package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableStatus;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O14 수기 주문 API — 부스 스코프·C3과 같은 검증 경로·"검증 먼저, 세션 나중"·채번 (명세서 O14·§2·§7-4, 감사 M4).
 * 채번·세션이 각자 트랜잭션으로 즉시 커밋되므로 롤백 대신 직접 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ManualOrderApiTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired OrderRepository orderRepository;
    @Autowired DailyCounterRepository dailyCounterRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired StaffCallRepository staffCallRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired TableRepository tableRepository;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired BoothRepository boothRepository;
    @Autowired BoothJwtProvider jwtProvider;

    private Long boothId;
    private Long tableId;
    private String tableToken;
    private Long deletedTableId;
    private Long otherBoothTableId;
    private Long kimchiId;
    private Long colaId;
    private Long soldOutId;
    private Long hiddenId;
    private Long otherBoothMenuId;
    private StaffAccountEntity staff;
    private String staffToken;
    private String otherBoothToken;
    private String superAdminToken;

    @BeforeEach
    void setUp() {
        cleanUp();

        BoothEntity booth = boothRepository.save(new BoothEntity("수기 부스", "카카오뱅크 3333-01-1234567 (홍길동)", null));
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("남의 부스", "국민은행 5678", null));
        boothId = booth.getId();

        TableEntity table = tableRepository.save(new TableEntity(booth, "A-3", "manual-token-a3"));
        tableId = table.getId();
        tableToken = table.getTableToken();
        TableEntity deleted = new TableEntity(booth, "D-1", "manual-token-d1");
        deleted.deactivate();
        deletedTableId = tableRepository.save(deleted).getId();
        otherBoothTableId = tableRepository.save(new TableEntity(otherBooth, "A-3", "manual-token-other")).getId();

        kimchiId = menuRepository.save(new MenuEntity(booth, "김치전", 8000, null, null, true)).getId();
        colaId = menuRepository.save(new MenuEntity(booth, "제로콜라", 5000, null, null, true)).getId();
        MenuEntity soldOut = new MenuEntity(booth, "파전", 12000, null, null, true);
        soldOut.updateSoldOut(true);
        soldOutId = menuRepository.save(soldOut).getId();
        hiddenId = menuRepository.save(new MenuEntity(booth, "숨김 메뉴", 3000, null, null, false)).getId();
        otherBoothMenuId = menuRepository.save(new MenuEntity(otherBooth, "남의 메뉴", 1000, null, null, true)).getId();

        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        LocalDateTime pwdAt = LocalDateTime.of(2026, 9, 1, 12, 0);
        staff = staffAccountRepository.save(new StaffAccountEntity(booth, "manual-staff", hash, pwdAt, StaffRole.STAFF));
        staffToken = jwtProvider.issue(staff, Instant.now());
        otherBoothToken = jwtProvider.issue(staffAccountRepository.save(
                new StaffAccountEntity(otherBooth, "manual-other", hash, pwdAt, StaffRole.ADMIN)), Instant.now());
        superAdminToken = jwtProvider.issue(staffAccountRepository.save(
                new StaffAccountEntity(null, "manual-super", hash, pwdAt, StaffRole.SUPER_ADMIN)), Instant.now());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        staffCallRepository.deleteAll();
        orderRepository.deleteAll();
        dailyCounterRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        menuRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    private MockHttpServletRequestBuilder manual(String token, String body) {
        return post("/api/v1/admin/orders")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder customerOrder(String sessionToken, String body) {
        return post("/api/v1/orders")
                .header("X-Session-Token", sessionToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String items(Long menuId, int qty) {
        return "[{\"menuId\":" + menuId + ",\"qty\":" + qty + "}]";
    }

    private String twoItems() {
        return "[{\"menuId\":" + kimchiId + ",\"qty\":2},{\"menuId\":" + colaId + ",\"qty\":3}]";
    }

    private String tableBody(Long tableId, String items) {
        return "{\"tableId\":" + tableId + ",\"items\":" + items + "}";
    }

    private String scanQr(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/table-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.sessionToken");
    }

    private Long orderIdOf(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.orderId")).longValue();
    }

    /** 실패한 수기 주문이 남기면 안 되는 흔적 — 주문·세션·채번 카운터·테이블 사용중 */
    private void assertNoSideEffects() {
        assertEquals(0, orderRepository.count());
        assertEquals(0, tableSessionRepository.count());
        assertEquals(0, dailyCounterRepository.count());   // 번호도 소모되지 않는다
        assertEquals(TableStatus.EMPTY, tableRepository.findById(tableId).orElseThrow().getStatus());
    }

    private void closeBooth() {
        BoothEntity booth = boothRepository.findById(boothId).orElseThrow();
        booth.updateOpen(false);
        boothRepository.save(booth);
    }

    // ── 웹 경로 동시성 ──────────────────────────────────────

    /**
     * 실제 요청은 OSIV로 요청 하나가 EntityManager 하나를 끝까지 공유한다. 세션 유니크 제약 위반 복구가
     * 그 공유 EntityManager 위에서도 되는지(서비스 직접 호출 테스트는 이걸 못 본다) 수기 주문 5건 + QR 스캔 3건을 한꺼번에 보낸다.
     */
    @Test
    void concurrentManualOrdersAndScansThroughWebLayerShareOneSession() throws Exception {
        String manualBody = tableBody(tableId, items(kimchiId, 1));
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> mockMvc.perform(manual(staffToken, manualBody)).andReturn());
        }
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> mockMvc.perform(post("/api/v1/table-sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tableToken\":\"" + tableToken + "\"}")).andReturn());
        }

        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (Callable<MvcResult> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return task.call();
            }));
        }
        ready.await();
        start.countDown();
        List<MvcResult> results = new ArrayList<>();
        try {
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        for (int i = 0; i < 5; i++) {
            assertEquals(201, results.get(i).getResponse().getStatus(), results.get(i).getResponse().getContentAsString());
        }
        for (int i = 5; i < 8; i++) {
            assertEquals(200, results.get(i).getResponse().getStatus(), results.get(i).getResponse().getContentAsString());
        }
        TableSessionEntity active = tableSessionRepository.findByTableIdAndEndedAtIsNull(tableId).orElseThrow();
        assertEquals(1, tableSessionRepository.count());
        Set<String> scannedTokens = results.subList(5, 8).stream()
                .map(r -> {
                    try {
                        return (String) JsonPath.read(r.getResponse().getContentAsString(), "$.sessionToken");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toSet());
        assertEquals(Set.of(active.getSessionToken()), scannedTokens);
        List<OrderEntity> orders = orderRepository.findAll();
        assertEquals(5, orders.size());
        assertEquals(Set.of(active.getId()), orders.stream().map(OrderEntity::getSessionId).collect(Collectors.toSet()));
        assertEquals(5, orders.stream().map(OrderEntity::getOrderNo).collect(Collectors.toSet()).size());
    }

    // ── 테이블 미지정 ───────────────────────────────────────

    @Test
    void createsUnassignedManualOrderWithMNumberAndC3ResponseShape() throws Exception {
        MvcResult result = mockMvc.perform(manual(staffToken, "{\"items\":" + twoItems() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("M-1"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.totalAmount").value(31000))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].menuName").value("김치전"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(8000))
                .andExpect(jsonPath("$.items[0].subtotal").value(16000))
                .andExpect(jsonPath("$.payment.method").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$.payment.bankAccount").value("카카오뱅크 3333-01-1234567 (홍길동)"))
                .andExpect(jsonPath("$.payment.depositorNameRule").value(Matchers.containsString("M-1")))
                .andExpect(jsonPath("$.createdAt").value(Matchers.endsWith("+09:00")))
                .andReturn();

        OrderEntity saved = orderRepository.findById(orderIdOf(result)).orElseThrow();
        assertTrue(saved.isManual());
        assertNull(saved.getSessionId());
        assertNull(saved.getTableLabel());
        assertNull(saved.getIdempotencyKey());
        assertEquals(1, saved.getOrderSeq());
        assertEquals(0, tableSessionRepository.count());   // 미지정 주문은 세션을 만들지 않는다
        assertEquals(TableStatus.EMPTY, tableRepository.findById(tableId).orElseThrow().getStatus());

        // O10에는 manual·tableLabel null로 보인다 (businessDate 생략 = 현재 영업일이라 방금 만든 주문이 잡힌다)
        mockMvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + staffToken))
                .andExpect(jsonPath("$.orders[0].orderNo").value("M-1"))
                .andExpect(jsonPath("$.orders[0].manual").value(true))
                .andExpect(jsonPath("$.orders[0].tableLabel").value(Matchers.nullValue()));
    }

    @Test
    void manualAndCustomerOrdersShareOneDailyCounter() throws Exception {
        String sessionToken = scanQr(tableToken);
        mockMvc.perform(customerOrder(sessionToken, "{\"items\":" + items(kimchiId, 1) + "}"))
                .andExpect(jsonPath("$.orderNo").value("A3-1"));
        mockMvc.perform(manual(staffToken, "{\"items\":" + items(kimchiId, 1) + "}"))
                .andExpect(jsonPath("$.orderNo").value("M-2"));
        mockMvc.perform(manual(staffToken, tableBody(tableId, items(kimchiId, 1))))
                .andExpect(jsonPath("$.orderNo").value("A3-3"));
        mockMvc.perform(manual(staffToken, "{\"items\":" + items(colaId, 1) + "}"))
                .andExpect(jsonPath("$.orderNo").value("M-4"));
    }

    // ── 테이블 지정 ─────────────────────────────────────────

    @Test
    void tableOrderCreatesSessionOccupiesTableAndCustomerScanSharesIt() throws Exception {
        MvcResult result = mockMvc.perform(manual(staffToken, tableBody(tableId, items(kimchiId, 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("A3-1"))
                .andExpect(jsonPath("$.totalAmount").value(16000))
                .andExpect(jsonPath("$.items[0].menuName").value("김치전"))
                .andReturn();

        OrderEntity saved = orderRepository.findById(orderIdOf(result)).orElseThrow();
        assertTrue(saved.isManual());
        assertEquals("A-3", saved.getTableLabel());
        assertNotNull(saved.getSessionId());
        assertNull(saved.getIdempotencyKey());

        TableSessionEntity active = tableSessionRepository.findByTableIdAndEndedAtIsNull(tableId).orElseThrow();
        assertEquals(active.getId(), saved.getSessionId());
        assertEquals(1, tableSessionRepository.count());
        assertEquals(TableStatus.OCCUPIED, tableRepository.findById(tableId).orElseThrow().getStatus());

        // 손님이 같은 QR을 찍으면 수기 주문이 붙은 그 세션으로 복원되고, 내 주문(C4)에 수기 주문이 보인다
        mockMvc.perform(post("/api/v1/table-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"" + tableToken + "\"}"))
                .andExpect(jsonPath("$.restored").value(true))
                .andExpect(jsonPath("$.sessionToken").value(active.getSessionToken()));
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", active.getSessionToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderNo").value("A3-1"));

        // O10 tableId 필터에도 잡힌다
        mockMvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + staffToken)
                        .param("tableId", tableId.toString()))
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].tableLabel").value("A-3"))
                .andExpect(jsonPath("$.orders[0].manual").value(true));
    }

    @Test
    void tableOrderJoinsExistingActiveSession() throws Exception {
        String sessionToken = scanQr(tableToken);
        Long sessionId = tableSessionRepository.findBySessionToken(sessionToken).orElseThrow().getId();

        MvcResult result = mockMvc.perform(manual(staffToken, tableBody(tableId, items(colaId, 1))))
                .andExpect(status().isCreated())
                .andReturn();

        assertEquals(sessionId, orderRepository.findById(orderIdOf(result)).orElseThrow().getSessionId());
        assertEquals(1, tableSessionRepository.count());
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", sessionToken))
                .andExpect(jsonPath("$.orders.length()").value(1));
    }

    @Test
    void tableOrderAfterEndedSessionStartsNewSession() throws Exception {
        String oldToken = scanQr(tableToken);
        TableSessionEntity old = tableSessionRepository.findBySessionToken(oldToken).orElseThrow();
        old.end(LocalDateTime.now(KST));
        tableSessionRepository.save(old);

        MvcResult result = mockMvc.perform(manual(staffToken, tableBody(tableId, items(colaId, 1))))
                .andExpect(status().isCreated())
                .andReturn();

        Long newSessionId = orderRepository.findById(orderIdOf(result)).orElseThrow().getSessionId();
        assertFalse(old.getId().equals(newSessionId));
        assertEquals(newSessionId, tableSessionRepository.findByTableIdAndEndedAtIsNull(tableId).orElseThrow().getId());
        assertEquals(2, tableSessionRepository.count());
    }

    @Test
    void manualOrderIsNotRateLimitedLikeC3() throws Exception {
        String sessionToken = scanQr(tableToken);
        for (int i = 0; i < 8; i++) {
            mockMvc.perform(customerOrder(sessionToken, "{\"items\":" + items(colaId, 1) + "}"))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(customerOrder(sessionToken, "{\"items\":" + items(colaId, 1) + "}"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(manual(staffToken, tableBody(tableId, items(colaId, 1))))
                .andExpect(status().isCreated());
    }

    // ── 부스 스코프(IDOR) ────────────────────────────────────

    @Test
    void otherBoothTableIsNotFoundAndLeavesNoSideEffects() throws Exception {
        mockMvc.perform(manual(staffToken, tableBody(otherBoothTableId, items(kimchiId, 1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        // 반대 방향 — 남의 부스 운영자가 우리 테이블에 주문을 넣으려 해도 404
        mockMvc.perform(manual(otherBoothToken, tableBody(tableId, items(otherBoothMenuId, 1))))
                .andExpect(status().isNotFound());

        assertNoSideEffects();
        assertEquals(TableStatus.EMPTY, tableRepository.findById(otherBoothTableId).orElseThrow().getStatus());
    }

    @Test
    void unknownOrDeletedTableIsNotFound() throws Exception {
        mockMvc.perform(manual(staffToken, tableBody(999999L, items(kimchiId, 1))))
                .andExpect(status().isNotFound());
        // soft delete된 테이블 — 운영자 화면(O3)에 없는 테이블에 주문이 붙으면 아무도 볼 수 없다
        mockMvc.perform(manual(staffToken, tableBody(deletedTableId, items(kimchiId, 1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertNoSideEffects();
        assertEquals(TableStatus.EMPTY, tableRepository.findById(deletedTableId).orElseThrow().getStatus());
    }

    @Test
    void boothIdInBodyIsNotTrusted() throws Exception {
        // 본문에 남의 boothId를 섞어도 주문은 JWT 부스에만 생긴다
        Long otherBoothId = boothRepository.findAll().stream()
                .filter(b -> !b.getId().equals(boothId)).findFirst().orElseThrow().getId();
        MvcResult result = mockMvc.perform(manual(staffToken,
                        "{\"boothId\":" + otherBoothId + ",\"items\":" + items(kimchiId, 1) + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        assertEquals(boothId, orderRepository.findById(orderIdOf(result)).orElseThrow().getBoothId());
    }

    @Test
    void otherBoothMenuIsRejectedAsInvalidRequestWithoutCreatingSession() throws Exception {
        mockMvc.perform(manual(staffToken, "{\"items\":" + items(otherBoothMenuId, 1) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(manual(staffToken, tableBody(tableId, items(otherBoothMenuId, 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertNoSideEffects();
    }

    // ── 인증 ────────────────────────────────────────────────

    @Test
    void rejectsMissingOrInvalidAuthorization() throws Exception {
        mockMvc.perform(post("/api/v1/admin/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tableBody(tableId, items(kimchiId, 1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(manual("not-a-jwt", tableBody(tableId, items(kimchiId, 1))))
                .andExpect(status().isUnauthorized());
        String forged = new BoothJwtProvider("attacker-controlled-secret-at-least-32-bytes").issue(staff, Instant.now());
        mockMvc.perform(manual(forged, tableBody(tableId, items(kimchiId, 1))))
                .andExpect(status().isUnauthorized());
        assertNoSideEffects();
    }

    @Test
    void rejectsSuperAdmin() throws Exception {
        mockMvc.perform(manual(superAdminToken, tableBody(tableId, items(kimchiId, 1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertNoSideEffects();
    }

    // ── C3과 같은 검증 — 실패하면 세션·OCCUPIED·채번이 남지 않는다 (감사 M4) ──

    @Test
    void soldOutAndHiddenMenusFailWholeOrderWithoutCreatingSession() throws Exception {
        mockMvc.perform(manual(staffToken, "{\"tableId\":" + tableId + ",\"items\":[{\"menuId\":" + kimchiId
                        + ",\"qty\":1},{\"menuId\":" + soldOutId + ",\"qty\":1},{\"menuId\":" + hiddenId + ",\"qty\":1}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SOLD_OUT"))
                .andExpect(jsonPath("$.error.details.length()").value(2))
                .andExpect(jsonPath("$.error.details[*].menuId",
                        Matchers.containsInAnyOrder(soldOutId.intValue(), hiddenId.intValue())));

        assertNoSideEffects();   // 실패한 주문이 빈 테이블을 사용중으로 바꾸면 안 된다
    }

    @Test
    void soldOutMenuIsConflictForUnassignedOrderToo() throws Exception {
        mockMvc.perform(manual(staffToken, "{\"items\":" + items(soldOutId, 1) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SOLD_OUT"));
        assertNoSideEffects();
    }

    @Test
    void closedBoothRejectsManualOrderWithoutCreatingSession() throws Exception {
        closeBooth();

        mockMvc.perform(manual(staffToken, tableBody(tableId, items(kimchiId, 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_CLOSED"));
        mockMvc.perform(manual(staffToken, "{\"items\":" + items(kimchiId, 1) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_CLOSED"));

        assertNoSideEffects();
    }

    @Test
    void rejectsInvalidItemsLikeC3WithoutCreatingSession() throws Exception {
        for (String items : List.of(
                "[]",
                items(kimchiId, 0),
                items(kimchiId, 31),
                items(999999L, 1),
                "[{\"menuId\":" + kimchiId + ",\"qty\":1},{\"menuId\":" + kimchiId + ",\"qty\":1}]",
                "[{\"menuId\":" + kimchiId + "}]",
                "[{\"qty\":1}]")) {
            mockMvc.perform(manual(staffToken, "{\"items\":" + items + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
            // 테이블을 지정해도 같은 400 — 그리고 세션을 만들지 않는다
            mockMvc.perform(manual(staffToken, tableBody(tableId, items)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        mockMvc.perform(manual(staffToken, "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(manual(staffToken, "{\"tableId\":" + tableId + "}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/orders")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest());

        assertNoSideEffects();
    }

    @Test
    void twentyOneKindsIsRejectedWithoutCreatingSession() throws Exception {
        List<Long> menuIds = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            menuIds.add(menuRepository.save(new MenuEntity(
                    boothRepository.findById(boothId).orElseThrow(), "메뉴" + i, 1000, null, null, true)).getId());
        }
        String items = menuIds.stream().map(id -> "{\"menuId\":" + id + ",\"qty\":1}")
                .collect(Collectors.joining(",", "[", "]"));
        mockMvc.perform(manual(staffToken, tableBody(tableId, items)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertNoSideEffects();
    }

    @Test
    void sameInputGivesSameAmountAndSnapshotsAsCustomerOrder() throws Exception {
        String sessionToken = scanQr(tableToken);
        String body = "{\"items\":" + twoItems() + "}";

        MvcResult customer = mockMvc.perform(customerOrder(sessionToken, body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult manualTable = mockMvc.perform(manual(staffToken, tableBody(tableId, twoItems())))
                .andExpect(status().isCreated()).andReturn();
        MvcResult manualNoTable = mockMvc.perform(manual(staffToken, body))
                .andExpect(status().isCreated()).andReturn();

        String c = customer.getResponse().getContentAsString();
        for (MvcResult m : List.of(manualTable, manualNoTable)) {
            String json = m.getResponse().getContentAsString();
            assertEquals((Integer) JsonPath.read(c, "$.totalAmount"), (Integer) JsonPath.read(json, "$.totalAmount"));
            assertEquals((List<Map<String, Object>>) JsonPath.read(c, "$.items"),
                    (List<Map<String, Object>>) JsonPath.read(json, "$.items"));
            assertEquals((String) JsonPath.read(c, "$.payment.bankAccount"), (String) JsonPath.read(json, "$.payment.bankAccount"));
            assertEquals((String) JsonPath.read(c, "$.payment.method"), (String) JsonPath.read(json, "$.payment.method"));
        }
        assertEquals(31000, (Integer) JsonPath.read(c, "$.totalAmount"));

        // 가격이 바뀌면 둘 다 새 가격으로 재계산한다 — 요청에 금액이 없으니 위변조할 자리도 없다
        MenuEntity kimchi = menuRepository.findById(kimchiId).orElseThrow();
        kimchi.updatePrice(9000);
        menuRepository.save(kimchi);
        mockMvc.perform(customerOrder(sessionToken, "{\"items\":" + items(kimchiId, 1) + ",\"totalAmount\":1}"))
                .andExpect(jsonPath("$.totalAmount").value(9000));
        mockMvc.perform(manual(staffToken, "{\"items\":" + items(kimchiId, 1) + ",\"totalAmount\":1}"))
                .andExpect(jsonPath("$.totalAmount").value(9000));
    }

    @Test
    void sameSoldOutInputGivesSameErrorAsCustomerOrder() throws Exception {
        String sessionToken = scanQr(tableToken);
        String body = "{\"items\":[{\"menuId\":" + soldOutId + ",\"qty\":1},{\"menuId\":" + kimchiId + ",\"qty\":1}]}";

        String c = mockMvc.perform(customerOrder(sessionToken, body))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        String m = mockMvc.perform(manual(staffToken, body))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        String mTable = mockMvc.perform(manual(staffToken, tableBody(tableId, "[{\"menuId\":" + soldOutId
                        + ",\"qty\":1},{\"menuId\":" + kimchiId + ",\"qty\":1}]")))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();

        assertEquals((Map<String, Object>) JsonPath.read(c, "$.error"), (Map<String, Object>) JsonPath.read(m, "$.error"));
        assertEquals((Map<String, Object>) JsonPath.read(c, "$.error"), (Map<String, Object>) JsonPath.read(mTable, "$.error"));
        assertEquals(0, orderRepository.count());
    }

    @Test
    void manualOrderCanBeProcessedByDashboardActions() throws Exception {
        MvcResult result = mockMvc.perform(manual(staffToken, "{\"items\":" + items(kimchiId, 1) + "}"))
                .andExpect(status().isCreated()).andReturn();
        Long orderId = orderIdOf(result);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/payment", orderId)
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manual").value(true))
                .andExpect(jsonPath("$.tableLabel").value(Matchers.nullValue()));

        OrderEntity saved = orderRepository.findById(orderId).orElseThrow();
        assertEquals(PaymentStatus.PAID, saved.getPaymentStatus());
        assertEquals(OrderStatus.RECEIVED, saved.getStatus());
    }
}
