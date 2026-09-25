package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.dashboard.domain.CallReason;
import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.dashboard.dto.CallRequest;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.dashboard.service.CallService;
import com.boothlock.boothlock_server.global.error.CallCooldownException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** C6 직원 호출(세션 토큰 인증)·O15 호출 확인(JWT + 부스 스코프) API 테스트 (명세서 C6·O15·§7-21) */
@SpringBootTest
@AutoConfigureMockMvc
class CallApiTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SESSION_TOKEN = "session-token-1";

    @Autowired MockMvc mockMvc;
    @Autowired StaffCallRepository staffCallRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired TableRepository tableRepository;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired CallService callService;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long sessionId;
    private String staffToken;
    private String otherBoothToken;
    private String superAdminToken;
    private TableSessionEntity otherBoothSession;

    @BeforeEach
    void setUp() {
        cleanUp();

        LocalDateTime now = LocalDateTime.now(KST);
        BoothEntity booth = boothRepository.save(new BoothEntity("호출 부스", "은행 1234", null));
        TableEntity table = tableRepository.save(new TableEntity(booth, "A-1", "table-token-1"));
        TableSessionEntity session = tableSessionRepository.save(new TableSessionEntity(table, SESSION_TOKEN, now));
        sessionId = session.getId();

        BoothEntity otherBooth = boothRepository.save(new BoothEntity("남의 부스", "은행 5678", null));
        TableEntity otherTable = tableRepository.save(new TableEntity(otherBooth, "B-1", "table-token-2"));
        otherBoothSession = tableSessionRepository.save(new TableSessionEntity(otherTable, "session-token-2", now));

        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        LocalDateTime pwdAt = LocalDateTime.of(2026, 9, 1, 12, 0);
        staffToken = jwtProvider.issue(staffAccountRepository.save(
                new StaffAccountEntity(booth, "call-staff", hash, pwdAt, StaffRole.STAFF)), Instant.now());
        otherBoothToken = jwtProvider.issue(staffAccountRepository.save(
                new StaffAccountEntity(otherBooth, "other-staff", hash, pwdAt, StaffRole.ADMIN)), Instant.now());
        superAdminToken = jwtProvider.issue(staffAccountRepository.save(
                new StaffAccountEntity(null, "call-super", hash, pwdAt, StaffRole.SUPER_ADMIN)), Instant.now());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        staffCallRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    private StaffCallEntity newCall(TableSessionEntity session) {
        return staffCallRepository.save(new StaffCallEntity(session, CallReason.HELP, LocalDateTime.now(KST)));
    }

    // ── C6 직원 호출 ────────────────────────────────────────

    @Test
    void createsCallForTokenSession() throws Exception {
        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", SESSION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.callId").exists())
                .andExpect(jsonPath("$.reason").value("HELP"))
                .andExpect(jsonPath("$.createdAt").exists());

        assertEquals(1, staffCallRepository.count());
        StaffCallEntity saved = staffCallRepository.findAll().get(0);
        assertEquals(sessionId, saved.getSession().getId());
        assertFalse(saved.isAcked());
    }

    @Test
    void callCountsAsSessionActivity() throws Exception {
        // 좌석 현황(E1)은 lastActivityAt으로 빈자리를 판정한다 — 호출만 하고 폴링이 없는 테이블이 빈자리로 잡히면 안 된다 (명세서 §1.2)
        // 유휴 임계(3시간) 안쪽으로 둔다 — 임계를 넘긴 세션(미결제 없음)은 인증 계층이 410으로 거절한다(TableSessionAuthService)
        LocalDateTime stale = LocalDateTime.now(KST).minusHours(2);
        jdbcTemplate.update("update table_session set started_at = ?, last_activity_at = ? where id = ?", stale, stale, sessionId);
        LocalDateTime before = LocalDateTime.now(KST).minusSeconds(1);

        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", SESSION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"WATER\"}"))
                .andExpect(status().isCreated());

        LocalDateTime after = tableSessionRepository.findById(sessionId).orElseThrow().getLastActivityAt();
        assertTrue(after.isAfter(before), "lastActivityAt이 갱신되지 않음: " + after);
    }

    @Test
    void attachesCallToTokenSessionNotToAnyRequestedSession() throws Exception {
        // 남의 테이블 세션 id를 파라미터로 끼워 넣어도 호출이 그쪽에 붙으면 안 된다 — 400으로 거절
        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", SESSION_TOKEN)
                        .param("sessionId", otherBoothSession.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertEquals(0, staffCallRepository.count());
    }

    @Test
    void rejectsSessionIdParamWithoutTokenAsUnauthorized() throws Exception {
        // 인증 전환 전 방식(파라미터만) — 헤더 누락이 먼저라 401
        mockMvc.perform(post("/api/v1/calls")
                        .param("sessionId", sessionId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertEquals(0, staffCallRepository.count());
    }

    @Test
    void rejectsMissingSessionTokenHeader() throws Exception {
        mockMvc.perform(post("/api/v1/calls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsUnknownSessionTokenWithGone() throws Exception {
        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", "no-such-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));

        assertEquals(0, staffCallRepository.count());
    }

    @Test
    void rejectsEndedSessionWithGone() throws Exception {
        TableSessionEntity session = tableSessionRepository.findById(sessionId).orElseThrow();
        session.end(LocalDateTime.now(KST));
        tableSessionRepository.save(session);

        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", SESSION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));

        assertEquals(0, staffCallRepository.count());
    }

    @Test
    void rejectsRecallWithinThirtySeconds() throws Exception {
        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", SESSION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isCreated());

        // 같은 사유(HELP)로 재호출 — v0.6.11부터 쿨다운은 사유별이라 "같은 사유"로 고정해야 이 테스트가 의미가 있다
        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", SESSION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("CALL_COOLDOWN"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").exists());

        assertEquals(1, staffCallRepository.count());
    }

    @Test
    void cooldownIsPerReasonNotJustPerSession() throws Exception {
        // v0.6.11 — HELP 쿨다운 중이어도 PAYMENT(결제확인)는 막히면 안 된다. 방금 입금을 알렸는데 다른 이유로
        // 최근 호출했다고 눌러도 반응이 없으면, 승인 대기 중인 손님이 계속 기다리게 된다
        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", SESSION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", SESSION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"PAYMENT\"}"))
                .andExpect(status().isCreated());

        assertEquals(2, staffCallRepository.count());
    }

    @Test
    void cooldownIsPerSession() throws Exception {
        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", SESSION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", "session-token-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isCreated());

        assertEquals(2, staffCallRepository.count());
    }

    @Test
    void serializesConcurrentCallsForSameSession() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<String> attempt = () -> {
            ready.countDown();
            start.await();
            try {
                callService.create(sessionId, new CallRequest(CallReason.HELP));
                return "CREATED";
            } catch (CallCooldownException e) {
                return "COOLDOWN";
            }
        };

        List<Future<String>> futures = List.of(executor.submit(attempt), executor.submit(attempt));
        ready.await();
        start.countDown();
        List<String> results = futures.stream()
                .map(f -> {
                    try {
                        return f.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());
        executor.shutdown();

        assertEquals(1, results.stream().filter("CREATED"::equals).count());
        assertEquals(1, results.stream().filter("COOLDOWN"::equals).count());
        assertEquals(1, staffCallRepository.count());
    }

    @Test
    void rejectsMissingReason() throws Exception {
        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", SESSION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertEquals(0, staffCallRepository.count());
    }

    @Test
    void rejectsInvalidReasonValue() throws Exception {
        mockMvc.perform(post("/api/v1/calls")
                        .header("X-Session-Token", SESSION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"URGENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertEquals(0, staffCallRepository.count());
    }

    // ── O15 호출 확인 ────────────────────────────────────────

    @Test
    void acksCallAndIsIdempotent() throws Exception {
        StaffCallEntity call = newCall(tableSessionRepository.findById(sessionId).orElseThrow());

        mockMvc.perform(patch("/api/v1/admin/calls/{callId}/ack", call.getId())
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callId").value(call.getId()))
                .andExpect(jsonPath("$.acked").value(true));
        mockMvc.perform(patch("/api/v1/admin/calls/{callId}/ack", call.getId())
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acked").value(true));

        assertTrue(staffCallRepository.findById(call.getId()).orElseThrow().isAcked());
    }

    @Test
    void acksUnknownCallReturnsNotFound() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/calls/{callId}/ack", 999999L)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void hidesOtherBoothCallAsNotFoundAndLeavesItUnacked() throws Exception {
        StaffCallEntity otherCall = newCall(otherBoothSession);

        mockMvc.perform(patch("/api/v1/admin/calls/{callId}/ack", otherCall.getId())
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertFalse(staffCallRepository.findById(otherCall.getId()).orElseThrow().isAcked());
    }

    @Test
    void rejectsAckWithoutAuthorization() throws Exception {
        StaffCallEntity call = newCall(tableSessionRepository.findById(sessionId).orElseThrow());

        mockMvc.perform(patch("/api/v1/admin/calls/{callId}/ack", call.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(patch("/api/v1/admin/calls/{callId}/ack", call.getId())
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        assertFalse(staffCallRepository.findById(call.getId()).orElseThrow().isAcked());
    }

    @Test
    void rejectsAckForSuperAdmin() throws Exception {
        StaffCallEntity call = newCall(tableSessionRepository.findById(sessionId).orElseThrow());

        mockMvc.perform(patch("/api/v1/admin/calls/{callId}/ack", call.getId())
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertFalse(staffCallRepository.findById(call.getId()).orElseThrow().isAcked());
    }

    @Test
    void otherBoothAdminCanAckOwnCallOnly() throws Exception {
        StaffCallEntity mine = newCall(tableSessionRepository.findById(sessionId).orElseThrow());
        StaffCallEntity theirs = newCall(otherBoothSession);

        mockMvc.perform(patch("/api/v1/admin/calls/{callId}/ack", theirs.getId())
                        .header("Authorization", "Bearer " + otherBoothToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/admin/calls/{callId}/ack", mine.getId())
                        .header("Authorization", "Bearer " + otherBoothToken))
                .andExpect(status().isNotFound());

        assertTrue(staffCallRepository.findById(theirs.getId()).orElseThrow().isAcked());
        assertFalse(staffCallRepository.findById(mine.getId()).orElseThrow().isAcked());
    }
}
