package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.dashboard.domain.CallReason;
import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O10 실시간 대시보드 API — JWT 인증(§7-21)·옛 boothId 파라미터 400·tableId 필터·businessDate 기본값(현재 영업일)·Limit 500.
 * 영업일은 테스트 실행 시점의 현재 영업일(06:00 경계)로 잡고 시각은 그 날짜의 KST 벽시계 값으로 시딩한다 —
 * 기본값이 "현재 영업일"이라 고정 날짜로는 기본값 경로를 검증할 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardQueryApiTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired OrderRepository orderRepository;
    @Autowired StaffCallRepository staffCallRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired TableRepository tableRepository;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired BoothRepository boothRepository;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired OrderNumberingService numberingService;
    @Autowired JdbcTemplate jdbcTemplate;

    private LocalDate day;        // 현재 영업일
    private LocalDate prevDay;    // 전 영업일
    private Long boothId;
    private Long otherBoothId;
    private Long tableA3Id;
    private Long tableB1Id;
    private Long emptyTableId;
    private Long deletedTableId;
    private Long otherBoothTableId;
    private Long a3FirstSessionId;
    private Long a3SecondSessionId;
    private Long b1SessionId;
    private String staffToken;
    private String adminToken;
    private String otherBoothToken;
    private String superAdminToken;
    private StaffAccountEntity staff;

    @BeforeEach
    void setUp() {
        cleanUp();
        day = numberingService.businessDateOf(LocalDateTime.now(KST));
        prevDay = day.minusDays(1);

        BoothEntity booth = boothRepository.save(new BoothEntity("대시보드 부스", "카카오뱅크 1234", null));
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("남의 부스", "국민은행 5678", null));
        boothId = booth.getId();
        otherBoothId = otherBooth.getId();

        TableEntity a3 = tableRepository.save(new TableEntity(booth, "A-3", "dash-token-a3"));
        TableEntity b1 = tableRepository.save(new TableEntity(booth, "B-1", "dash-token-b1"));
        TableEntity empty = tableRepository.save(new TableEntity(booth, "C-9", "dash-token-c9"));
        TableEntity deleted = new TableEntity(booth, "D-1", "dash-token-d1");
        deleted.deactivate();
        deleted = tableRepository.save(deleted);
        TableEntity otherTable = tableRepository.save(new TableEntity(otherBooth, "A-3", "dash-token-other"));
        tableA3Id = a3.getId();
        tableB1Id = b1.getId();
        emptyTableId = empty.getId();
        deletedTableId = deleted.getId();
        otherBoothTableId = otherTable.getId();

        // A-3에는 손님이 두 번 앉았다 — 먼저 앉은 세션은 종료, 지금 세션은 활성
        TableSessionEntity a3First = tableSessionRepository.save(
                new TableSessionEntity(a3, "dash-session-a3-1", prevDay.atTime(18, 0)));
        a3First.end(day.atTime(19, 0));
        tableSessionRepository.save(a3First);
        TableSessionEntity a3Second = tableSessionRepository.save(
                new TableSessionEntity(a3, "dash-session-a3-2", day.atTime(20, 0)));
        TableSessionEntity b1Session = tableSessionRepository.save(
                new TableSessionEntity(b1, "dash-session-b1", day.atTime(18, 0)));
        TableSessionEntity otherSession = tableSessionRepository.save(
                new TableSessionEntity(otherTable, "dash-session-other", day.atTime(18, 0)));
        a3FirstSessionId = a3First.getId();
        a3SecondSessionId = a3Second.getId();
        b1SessionId = b1Session.getId();

        newOrder(boothId, a3First.getId(), "A-3", "A3-1", prevDay, 1, prevDay.atTime(18, 30), false, OrderStatus.DONE);
        newOrder(boothId, a3First.getId(), "A-3", "A3-1", day, 1, day.atTime(18, 30), false, OrderStatus.RECEIVED);
        newOrder(boothId, a3Second.getId(), "A-3", "A3-2", day, 2, day.atTime(20, 30), false, OrderStatus.RECEIVED);
        newOrder(boothId, b1Session.getId(), "B-1", "B1-3", day, 3, day.atTime(20, 40), false, OrderStatus.RECEIVED);
        newOrder(boothId, null, null, "M-4", day, 4, day.atTime(20, 50), true, OrderStatus.RECEIVED);
        newOrder(otherBoothId, otherSession.getId(), "A-3", "A3-1", day, 1, day.atTime(19, 0), false, OrderStatus.RECEIVED);

        staffCallRepository.save(new StaffCallEntity(b1Session, CallReason.WATER, day.atTime(20, 45)));
        staffCallRepository.save(new StaffCallEntity(otherSession, CallReason.HELP, day.atTime(20, 45)));

        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        LocalDateTime pwdAt = LocalDateTime.of(2026, 9, 1, 12, 0);
        staff = staffAccountRepository.save(new StaffAccountEntity(booth, "dash-staff", hash, pwdAt, StaffRole.STAFF));
        staffToken = jwtProvider.issue(staff, Instant.now());
        adminToken = jwtProvider.issue(staffAccountRepository.save(
                new StaffAccountEntity(booth, "dash-admin", hash, pwdAt, StaffRole.ADMIN)), Instant.now());
        otherBoothToken = jwtProvider.issue(staffAccountRepository.save(
                new StaffAccountEntity(otherBooth, "dash-other", hash, pwdAt, StaffRole.STAFF)), Instant.now());
        superAdminToken = jwtProvider.issue(staffAccountRepository.save(
                new StaffAccountEntity(null, "dash-super", hash, pwdAt, StaffRole.SUPER_ADMIN)), Instant.now());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        orderRepository.deleteAll();
        staffCallRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    private OrderEntity newOrder(Long boothId, Long sessionId, String tableLabel, String orderNo, LocalDate businessDate,
                                 int seq, LocalDateTime createdAt, boolean manual, OrderStatus status) {
        OrderEntity order = new OrderEntity(boothId, sessionId, orderNo, businessDate, seq,
                manual ? null : "dash-idem-" + boothId + "-" + businessDate + "-" + seq,
                8000, manual, tableLabel, createdAt);
        order.addItem(new OrderItemEntity(3L, "김치전", 8000, 1));
        OrderEntity saved = orderRepository.save(order);
        if (status != OrderStatus.RECEIVED) {
            jdbcTemplate.update("update orders set status = ? where id = ?", status.name(), saved.getId());
        }
        return saved;
    }

    private MockHttpServletRequestBuilder dashboard(String token) {
        return get("/api/v1/admin/orders").header("Authorization", "Bearer " + token);
    }

    // ── 인증 ────────────────────────────────────────────────

    @Test
    void rejectsMissingAuthorization() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsLegacyBoothIdParamWithoutToken() throws Exception {
        // 인증 전환 전 호출 방식 그대로 — 자격 증명 없이 부스 주문이 나오면 안 된다
        mockMvc.perform(get("/api/v1/admin/orders").param("boothId", boothId.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.orders").doesNotExist());
    }

    @Test
    void rejectsInvalidAndForgedTokens() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/orders").header("Authorization", staffToken))   // Bearer 접두어 없음
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() throws Exception {
        String forged = new BoothJwtProvider("attacker-controlled-secret-at-least-32-bytes").issue(staff, Instant.now());
        mockMvc.perform(dashboard(forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.orders").doesNotExist());
    }

    @Test
    void rejectsTokenIssuedBeforePasswordChange() throws Exception {
        jdbcTemplate.update("update staff_account set password_changed_at = ? where id = ?",
                LocalDateTime.of(2026, 9, 2, 12, 0), staff.getId());
        mockMvc.perform(dashboard(staffToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTokenWhoseBoothClaimNoLongerMatchesAccount() throws Exception {
        // 계정이 다른 부스로 옮겨졌다면 옛 토큰(boothId=원래 부스)으로 새 부스 주문을 보면 안 된다
        jdbcTemplate.update("update staff_account set booth_id = ? where id = ?", otherBoothId, staff.getId());
        mockMvc.perform(dashboard(staffToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.orders").doesNotExist());
    }

    @Test
    void legacyBoothIdParamIsRejectedBeforeTokenCheck() throws Exception {
        // 파라미터 검사가 인증보다 앞선다 — 잘못된 토큰 + boothId는 400. 어느 쪽이든 주문은 나가지 않는다
        mockMvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer not-a-jwt")
                        .param("boothId", boothId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.orders").doesNotExist());
    }

    @Test
    void rejectsSuperAdminWithForbidden() throws Exception {
        mockMvc.perform(dashboard(superAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void rejectsBoothIdParamEvenWithValidToken() throws Exception {
        // A 부스 토큰 + B 부스 boothId — 우회 시도는 조용히 무시하지 않고 400으로 거절
        mockMvc.perform(dashboard(staffToken).param("boothId", otherBoothId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.orders").doesNotExist());
        mockMvc.perform(dashboard(staffToken).param("boothId", boothId.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staffAndAdminSeeOnlyTheirBoothFromJwt() throws Exception {
        for (String token : new String[] {staffToken, adminToken}) {
            mockMvc.perform(dashboard(token))
                    .andExpect(status().isOk())
                    // businessDate 생략 → 현재 영업일 4건 (전 영업일 A3-1은 제외)
                    .andExpect(jsonPath("$.orders.length()").value(4))
                    .andExpect(jsonPath("$.orders[*].orderNo",
                            Matchers.containsInAnyOrder("A3-1", "A3-2", "B1-3", "M-4")))
                    .andExpect(jsonPath("$.calls.length()").value(1))
                    .andExpect(jsonPath("$.calls[0].tableLabel").value("B-1"));
        }
    }

    @Test
    void otherBoothTokenSeesOnlyOtherBooth() throws Exception {
        mockMvc.perform(dashboard(otherBoothToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderNo").value("A3-1"))
                .andExpect(jsonPath("$.orders[0].createdAt").value(day.atTime(19, 0) + ":00+09:00"))
                .andExpect(jsonPath("$.calls.length()").value(1))
                .andExpect(jsonPath("$.calls[0].reason").value("HELP"));
    }

    // ── businessDate 기본값 = 현재 영업일 ───────────────────

    @Test
    void omittedBusinessDateDefaultsToCurrentBusinessDay() throws Exception {
        // 프론트가 달력 날짜를 보내던 것을 빼도 같은 결과 — 00~06시에는 달력 날짜와 달라 전날 영업일 주문이 유지된다
        mockMvc.perform(dashboard(staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(4))
                .andExpect(jsonPath("$.orders[*].orderNo", Matchers.not(Matchers.hasItem("A3-1-prev"))));
        mockMvc.perform(dashboard(staffToken).param("businessDate", day.toString()))
                .andExpect(jsonPath("$.orders.length()").value(4));
        // 전 영업일은 명시해야 나온다 — "전체 날짜" 조회는 더 이상 없다
        mockMvc.perform(dashboard(staffToken).param("businessDate", prevDay.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderNo").value("A3-1"))
                .andExpect(jsonPath("$.orders[0].status").value("DONE"));
    }

    @Test
    void rejectsMalformedBusinessDate() throws Exception {
        mockMvc.perform(dashboard(staffToken).param("businessDate", "2026/09/15"))
                .andExpect(status().isBadRequest());
    }

    // ── tableLabel 응답 ─────────────────────────────────────

    @Test
    void returnsTableLabelSnapshotAndNullForUnassignedManualOrder() throws Exception {
        mockMvc.perform(dashboard(staffToken).param("businessDate", day.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(4))
                // 최신순 — M-4, B1-3, A3-2, A3-1
                .andExpect(jsonPath("$.orders[0].orderNo").value("M-4"))
                .andExpect(jsonPath("$.orders[0].tableLabel").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[0].manual").value(true))
                .andExpect(jsonPath("$.orders[1].tableLabel").value("B-1"))
                .andExpect(jsonPath("$.orders[2].tableLabel").value("A-3"))
                .andExpect(jsonPath("$.orders[2].manual").value(false))
                .andExpect(jsonPath("$.orders[3].tableLabel").value("A-3"))
                .andExpect(jsonPath("$.orders[3].items[0].itemId").exists());
    }

    // ── tableId 필터 ────────────────────────────────────────

    @Test
    void tableFilterReturnsAllSessionsOfTableWithinBusinessDate() throws Exception {
        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", tableA3Id.toString())
                        .param("businessDate", day.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].orderNo").value("A3-2"))   // 지금 세션
                .andExpect(jsonPath("$.orders[1].orderNo").value("A3-1"));  // 종료된 이전 세션도 포함
    }

    @Test
    void tableFilterWithoutBusinessDateUsesCurrentBusinessDay() throws Exception {
        // 전 영업일의 A3-1(DONE)은 빠진다 — 결제 모달(PaymentModal.tsx)이 날짜 없이 불러도 하루치만
        mockMvc.perform(dashboard(staffToken).param("tableId", tableA3Id.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2));
    }

    @Test
    void tableFilterExcludesOtherTablesAndUnassignedManualOrders() throws Exception {
        mockMvc.perform(dashboard(staffToken).param("tableId", tableB1Id.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderNo").value("B1-3"))
                // 호출 목록은 테이블 필터와 무관하게 부스 전체 미확인 호출 (기존 동작 유지)
                .andExpect(jsonPath("$.calls.length()").value(1));
    }

    @Test
    void tableFilterCombinesWithOtherFilters() throws Exception {
        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", tableA3Id.toString())
                        .param("q", "A3-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderNo").value("A3-2"));
        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", tableA3Id.toString())
                        .param("status", "DONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(0));
    }

    @Test
    void tableWithoutOrdersReturnsEmptyListNotNotFound() throws Exception {
        mockMvc.perform(dashboard(staffToken).param("tableId", emptyTableId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(0));
    }

    @Test
    void otherBoothTableIsNotFound() throws Exception {
        mockMvc.perform(dashboard(staffToken).param("tableId", otherBoothTableId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.orders").doesNotExist());
        mockMvc.perform(dashboard(otherBoothToken).param("tableId", tableA3Id.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownOrDeletedTableIsNotFound() throws Exception {
        mockMvc.perform(dashboard(staffToken).param("tableId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        // soft delete된 테이블은 O3 목록에 없으니 대시보드 필터에서도 없는 테이블로 본다
        mockMvc.perform(dashboard(staffToken).param("tableId", deletedTableId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsNonNumericTableId() throws Exception {
        mockMvc.perform(dashboard(staffToken).param("tableId", "abc"))
                .andExpect(status().isBadRequest());
    }

    // ── sessionId 노출 ─────────────────────────────────────

    @Test
    void exposesSessionIdMatchingO3SessionAndNullForUnassignedManualOrder() throws Exception {
        mockMvc.perform(dashboard(staffToken).param("businessDate", day.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].orderNo").value("M-4"))
                .andExpect(jsonPath("$.orders[0].sessionId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[1].orderNo").value("B1-3"))
                .andExpect(jsonPath("$.orders[1].sessionId").value(b1SessionId.intValue()))
                .andExpect(jsonPath("$.orders[2].orderNo").value("A3-2"))
                .andExpect(jsonPath("$.orders[2].sessionId").value(a3SecondSessionId.intValue()))
                .andExpect(jsonPath("$.orders[3].orderNo").value("A3-1"))
                .andExpect(jsonPath("$.orders[3].sessionId").value(a3FirstSessionId.intValue()));

        // O3 session.id와 같은 값이다 — 테이블-홈 카드가 시각 비교 없이 "지금 앉은 손님" 주문을 고르는 근거
        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.label=='A-3')].session.id").value(Matchers.contains(a3SecondSessionId.intValue())))
                .andExpect(jsonPath("$.tables[?(@.label=='B-1')].session.id").value(Matchers.contains(b1SessionId.intValue())));
    }

    // ── activeSessionOnly ──────────────────────────────────

    @Test
    void activeSessionOnlyReturnsOnlyOrdersOfTheOpenSession() throws Exception {
        // 같은 영업일에 종료된 이전 세션 주문(A3-1, day)은 빠지고 지금 세션 주문(A3-2)만
        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", tableA3Id.toString())
                        .param("activeSessionOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderNo").value("A3-2"))
                .andExpect(jsonPath("$.orders[0].sessionId").value(a3SecondSessionId.intValue()))
                // 호출 목록은 그대로 부스 전체
                .andExpect(jsonPath("$.calls.length()").value(1));
    }

    @Test
    void activeSessionOnlyFalseOrOmittedKeepsAllSessions() throws Exception {
        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", tableA3Id.toString())
                        .param("activeSessionOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2));
        mockMvc.perform(dashboard(staffToken).param("tableId", tableA3Id.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2));
    }

    @Test
    void activeSessionOnlyIsEmptyWhenTableHasNoOpenSession() throws Exception {
        // 세션이 한 번도 없던 테이블
        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", emptyTableId.toString())
                        .param("activeSessionOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(0));

        // 지금 세션을 퇴실로 닫으면 — 그 세션 주문은 있어도 빈 목록 (다음 손님 결제 모달에 앞 손님 주문이 섞이지 않게)
        TableSessionEntity a3Second = tableSessionRepository.findById(a3SecondSessionId).orElseThrow();
        a3Second.end(day.atTime(21, 0));
        tableSessionRepository.save(a3Second);
        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", tableA3Id.toString())
                        .param("activeSessionOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(0));
        // 필터 없이 부르면 여전히 두 세션 주문이 다 보인다 — 기존 동작 그대로
        mockMvc.perform(dashboard(staffToken).param("tableId", tableA3Id.toString()))
                .andExpect(jsonPath("$.orders.length()").value(2));
    }

    @Test
    void activeSessionOnlyTreatsNonZeroEndedAtKeyAsEnded() throws Exception {
        // 열린 세션의 표지는 ended_at_key = 0(uq_session_active) — ended_at만 보면 키가 채워진 세션을 열린 것으로 잘못 읽는다.
        // 종료 UPDATE가 두 컬럼을 함께 쓰므로 정상 데이터에선 둘이 같지만, 조건은 인덱스를 태우는 ended_at_key 쪽이 기준이다
        jdbcTemplate.update("update table_session set ended_at_key = id where id = ?", a3SecondSessionId);

        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", tableA3Id.toString())
                        .param("activeSessionOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(0));
    }

    @Test
    void activeSessionOnlyCombinesWithOtherFiltersAndBusinessDate() throws Exception {
        newOrder(boothId, a3SecondSessionId, "A-3", "A3-9", day, 9, day.atTime(22, 0), false, OrderStatus.DONE);
        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", tableA3Id.toString())
                        .param("activeSessionOnly", "true")
                        .param("status", "DONE"))
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderNo").value("A3-9"));
        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", tableA3Id.toString())
                        .param("activeSessionOnly", "true")
                        .param("q", "A3-2"))
                .andExpect(jsonPath("$.orders.length()").value(1));
        // 영업일 필터는 그대로 적용된다 — 지금 세션 주문이라도 전 영업일 조회에는 없다
        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", tableA3Id.toString())
                        .param("activeSessionOnly", "true")
                        .param("businessDate", prevDay.toString()))
                .andExpect(jsonPath("$.orders.length()").value(0));
    }

    /**
     * 06:00 경계 — 열린 세션에 전 영업일 미결제가 남아 있으면 결제 모달(activeSessionOnly, businessDate 생략)에도 보여야 한다.
     * O24 일괄 입금 대상은 영업일과 무관하게 열린 세션의 미결제 전부라, 모달이 현재 영업일로 거르면 모달 합계와 서버 합계가 달라
     * 그 테이블은 O24가 영원히 409다. 모달 합계를 expectedTotal로 보낸 O24가 통과하는지까지 본다
     */
    @Test
    void activeSessionOnlyIncludesPreviousBusinessDayOrdersOfTheOpenSessionLikeO24() throws Exception {
        newOrder(boothId, a3SecondSessionId, "A-3", "A3-8", prevDay, 8, prevDay.atTime(23, 0), false, OrderStatus.RECEIVED);

        String body = mockMvc.perform(dashboard(staffToken)
                        .param("tableId", tableA3Id.toString())
                        .param("activeSessionOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[*].orderNo").value(Matchers.containsInAnyOrder("A3-2", "A3-8")))
                .andReturn().getResponse().getContentAsString();
        List<Integer> totals = JsonPath.read(body, "$.orders[*].totalAmount");
        int modalTotal = totals.stream().mapToInt(Integer::intValue).sum();

        mockMvc.perform(post("/api/v1/admin/orders/table-payment")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":" + tableA3Id + ",\"expectedTotal\":" + modalTotal
                                + ",\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isOk());

        // 대시보드 기본 목록(테이블 필터 없음)은 예전처럼 현재 영업일만이다
        mockMvc.perform(dashboard(staffToken).param("q", "A3-8"))
                .andExpect(jsonPath("$.orders.length()").value(0));
    }

    @Test
    void activeSessionOnlyWithoutTableIdIsBadRequest() throws Exception {
        mockMvc.perform(dashboard(staffToken).param("activeSessionOnly", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(dashboard(staffToken).param("activeSessionOnly", "maybe").param("tableId", tableA3Id.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void activeSessionOnlyKeepsTableScopeChecks() throws Exception {
        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", otherBoothTableId.toString())
                        .param("activeSessionOnly", "true"))
                .andExpect(status().isNotFound());
        mockMvc.perform(dashboard(staffToken)
                        .param("tableId", deletedTableId.toString())
                        .param("activeSessionOnly", "true"))
                .andExpect(status().isNotFound());
        mockMvc.perform(dashboard(superAdminToken)
                        .param("tableId", tableA3Id.toString())
                        .param("activeSessionOnly", "true"))
                .andExpect(status().isForbidden());
    }

    // ── Limit 500 ───────────────────────────────────────────

    @Test
    void capsNonReceivedListsAtFiveHundredButKeepsReceivedUnlimited() throws Exception {
        List<OrderEntity> bulk = new ArrayList<>();
        for (int seq = 100; seq < 601; seq++) {          // DONE 501건
            OrderEntity o = new OrderEntity(boothId, null, "M-" + seq, day, seq, null, 1000, true, null, day.atTime(12, 0));
            o.addItem(new OrderItemEntity(3L, "김치전", 1000, 1));
            bulk.add(o);
        }
        for (int seq = 700; seq < 1201; seq++) {         // RECEIVED 501건
            OrderEntity o = new OrderEntity(boothId, null, "M-" + seq, day, seq, null, 1000, true, null, day.atTime(12, 0));
            o.addItem(new OrderItemEntity(3L, "김치전", 1000, 1));
            bulk.add(o);
        }
        orderRepository.saveAll(bulk);
        jdbcTemplate.update("update orders set status = 'DONE' where booth_id = ? and order_seq between 100 and 600", boothId);

        mockMvc.perform(dashboard(staffToken).param("status", "DONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(500));
        mockMvc.perform(dashboard(staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(500));
        mockMvc.perform(dashboard(staffToken).param("status", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(501 + 4));   // 시딩 RECEIVED 4건 포함, 상한 없음
        assertEquals(1002 + 6, orderRepository.count());
    }

    // ── 취소 주문 삭제(hidden) — Limit 500과의 상호작용 ──────

    @Test
    void hiddenCanceledOrdersAreExcludedBeforeTheFiveHundredLimitIsApplied() throws Exception {
        // 회귀 재현: hidden 제외를 애플리케이션(Java)에서만 하면 "최근 500건"을 DB가 먼저 잘라버려서,
        // 그 500건이 전부 hidden이면 501번째로 밀린 진짜 보여줘야 할 취소 주문이 통째로 사라진다.
        // hidden 제외가 DB 쿼리(WHERE)에서 limit과 함께 걸려야 이 케이스에서 살아남는다.
        OrderEntity visible = new OrderEntity(
                boothId, null, "M-VISIBLE", day, 9000, null, 1000, true, null, day.atTime(6, 0));
        visible.addItem(new OrderItemEntity(3L, "김치전", 1000, 1));
        List<OrderEntity> bulk = new ArrayList<>();
        bulk.add(visible);
        for (int seq = 9001; seq <= 9500; seq++) {   // 500건, visible보다 전부 최근 시각
            OrderEntity hidden = new OrderEntity(
                    boothId, null, "M-HIDDEN-" + seq, day, seq, null, 1000, true, null,
                    day.atTime(6, 0).plusMinutes(seq - 9000));
            hidden.addItem(new OrderItemEntity(3L, "김치전", 1000, 1));
            bulk.add(hidden);
        }
        orderRepository.saveAll(bulk);
        jdbcTemplate.update(
                "update orders set status = 'CANCELED' where booth_id = ? and order_seq between 9000 and 9500", boothId);
        jdbcTemplate.update(
                "update orders set hidden = true where booth_id = ? and order_seq between 9001 and 9500", boothId);

        mockMvc.perform(dashboard(staffToken).param("status", "CANCELED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderNo").value("M-VISIBLE"));
    }

    @Test
    void paymentStatusFilterStillReturnsNonHiddenRefundNeededOrders() throws Exception {
        // 삭제(hidden)는 주문현황 목록 전용이어야 한다 — paymentStatus=REFUND_NEEDED로 찾는 조회에서도
        // hidden이 아닌 환불필요 주문은 그대로 나와야 하고, hidden인 것만 빠져야 한다
        OrderEntity visible = new OrderEntity(
                boothId, null, "M-RN-VISIBLE", day, 9600, null, 4000, true, null, day.atTime(12, 0));
        OrderEntity hidden = new OrderEntity(
                boothId, null, "M-RN-HIDDEN", day, 9601, null, 4000, true, null, day.atTime(12, 1));
        orderRepository.saveAll(List.of(visible, hidden));
        jdbcTemplate.update(
                "update orders set status = 'CANCELED', payment_status = 'REFUND_NEEDED' "
                        + "where booth_id = ? and order_seq in (9600, 9601)", boothId);
        jdbcTemplate.update("update orders set hidden = true where booth_id = ? and order_seq = 9601", boothId);

        mockMvc.perform(dashboard(staffToken).param("paymentStatus", "REFUND_NEEDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderNo").value("M-RN-VISIBLE"));
    }
}
