package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.global.seat.SeatIdlePolicy;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O3 좌석 현황의 유휴 임계 경계(±1초)와 쿼리 수 고정 검증 (명세서 O3·§7-19, DB스키마 원칙 14).
 * 유휴 정책의 시계를 고정해 경계 테스트가 실행 속도에 흔들리지 않게 한다 — 시계는 일부러 UTC로 준다.
 * 쿼리 수는 Hibernate 통계로 센다(이 테스트 컨텍스트에서만 켠다).
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // 홈 화면 목록(E1)의 10초 캐시를 끈다 — 쿼리 수와 경계 판정을 요청마다 새로 확인한다
        "boothlock.event.booths-cache-seconds=0"})
@AutoConfigureMockMvc
class TableStatusBoundaryTests {

    /** 고정 시각 2026-09-15 20:00:00 KST. 기본 임계 180분이면 idleSince는 17:00:00 */
    private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 9, 15, 20, 0);
    private static final LocalDateTime IDLE_SINCE = NOW_KST.minusMinutes(180);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        SeatIdlePolicy fixedSeatIdlePolicy() {
            return new SeatIdlePolicy(180, Clock.fixed(NOW_KST.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneOffset.UTC),
                    new OrderNumberingService(null));
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired DailyCounterRepository dailyCounterRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    private BoothEntity booth;
    private int orderSeq;

    @BeforeEach
    void setUp() {
        cleanUp();
        booth = boothRepository.save(new BoothEntity("경계 부스", "은행 1234", null));
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

    private TableEntity occupiedTable(String label) {
        TableEntity table = new TableEntity(booth, label, "tok-boundary-" + label);
        table.occupy();
        return tableRepository.save(table);
    }

    private TableSessionEntity sessionLastActiveAt(TableEntity table, LocalDateTime lastActivityAt) {
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, "sess-boundary-" + table.getLabel(), lastActivityAt.minusMinutes(30)));
        tableSessionRepository.touchIfActive(session.getId(), lastActivityAt);
        return session;
    }

    private void unpaidOrder(Long sessionId) {
        orderSeq++;
        orderRepository.save(new OrderEntity(booth.getId(), sessionId, "B" + orderSeq, LocalDate.of(2026, 9, 15),
                orderSeq, "idem-boundary-" + orderSeq, 5000, false, NOW_KST));
    }

    // ── 유휴 임계 경계 ───────────────────────────────────

    @Test
    void idleThresholdBoundaryIsOneSecondSharp() throws Exception {
        sessionLastActiveAt(occupiedTable("A-1"), IDLE_SINCE.plusSeconds(1));    // 1초 안쪽 — 활성
        sessionLastActiveAt(occupiedTable("A-2"), IDLE_SINCE);                   // 정확히 경계 — 유휴(E1의 > 조건과 같음)
        sessionLastActiveAt(occupiedTable("A-3"), IDLE_SINCE.minusSeconds(1));   // 1초 바깥 — 유휴

        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0].label").value("A-1"))
                .andExpect(jsonPath("$.tables[0].session.lastActivityAt").value("2026-09-15T17:00:01+09:00"))
                .andExpect(jsonPath("$.tables[0].needsCleanup").value(false))
                .andExpect(jsonPath("$.tables[1].label").value("A-2"))
                .andExpect(jsonPath("$.tables[1].session").value(nullValue()))
                .andExpect(jsonPath("$.tables[1].needsCleanup").value(true))
                .andExpect(jsonPath("$.tables[2].label").value("A-3"))
                .andExpect(jsonPath("$.tables[2].session").value(nullValue()))
                .andExpect(jsonPath("$.tables[2].needsCleanup").value(true));

        // 같은 고정 시계를 E1도 쓴다 — 세 테이블 중 활성 1개, 빈자리 2개
        mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(jsonPath("$.booths[0].tables.total").value(3))
                .andExpect(jsonPath("$.booths[0].tables.empty").value(2));
    }

    // ── 쿼리 수 ─────────────────────────────────────────

    @Test
    void o3QueryCountDoesNotGrowWithTableCount() throws Exception {
        String token = login();
        seedTables(0, 3);
        long small = countStatements(() -> mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.tables.length()").value(3)));

        seedTables(3, 40);
        long large = countStatements(() -> mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.tables.length()").value(40)));

        System.out.println("[O3 query count] 3 tables=" + small + ", 40 tables=" + large);
        assertEquals(small, large, "테이블 수가 늘어도 O3 쿼리 수가 같아야 한다(N+1 금지)");
        // 운영자 인증 1(계정) + 테이블 1 + 열린 세션 1 + 미결제 건수 1
        assertEquals(4, large);
    }

    @Test
    void o22QueryCountIsFixed() throws Exception {
        String token = login();
        seedTables(0, 5);
        Long tableId = tableRepository.findByBoothId(booth.getId()).getFirst().getId();

        long statements = countStatements(() -> mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", tableId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"posX\":10,\"posY\":20}"))
                .andExpect(status().isOk()));

        System.out.println("[O22 query count] " + statements);
        // 인증 1 + 테이블 1 + 열린 세션 1 + 미결제 1 + 좌표 UPDATE 1
        assertEquals(5, statements);
    }

    /** from~to 라벨의 테이블마다 활성·유휴·종료 세션과 미결제 주문을 섞어 심는다 — 지연 로딩이 끼면 쿼리 수가 달라지게 */
    private void seedTables(int from, int to) {
        for (int i = from; i < to; i++) {
            TableEntity table = occupiedTable("T" + i);
            switch (i % 3) {
                case 0 -> unpaidOrder(sessionLastActiveAt(table, NOW_KST.minusMinutes(5)).getId());
                case 1 -> unpaidOrder(sessionLastActiveAt(table, NOW_KST.minusHours(5)).getId());
                default -> {
                    TableSessionEntity ended = sessionLastActiveAt(table, NOW_KST.minusHours(1));
                    ended.end(NOW_KST);
                    tableSessionRepository.save(ended);
                }
            }
        }
    }

    private long countStatements(ThrowingRunnable action) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        action.run();
        return statistics.getPrepareStatementCount();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"admin\",\"password\":\"password\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
