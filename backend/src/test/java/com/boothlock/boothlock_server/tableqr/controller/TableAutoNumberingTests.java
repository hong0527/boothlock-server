package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.dto.TableAdminResponse;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.service.TableAdminService;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "테이블 추가" 자동 채번의 견고성 (audit2 H3·H4).
 * main은 카운터(booth.next_table_seq)를 그대로 믿어, O2로 만든 "T-1"과 부딪히거나(H4) O17 부스 설정 저장이 카운터를
 * 옛 값으로 되돌리면(H3) 다음 추가가 유니크 위반 500으로 영영 굳었다. 여기서는 기존 라벨과 맞춰 채번하는지,
 * 카운터 갱신이 다른 부스 컬럼을 덮지 않는지, 20건 동시 추가가 전부 고유한지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TableAutoNumberingTests {

    @Autowired MockMvc mockMvc;
    @Autowired TableAdminService tableAdminService;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

    private BoothEntity booth;
    private String authorization;

    @BeforeEach
    void setUp() {
        cleanUp();
        booth = boothRepository.save(new BoothEntity("채번 부스", "은행 1234", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        StaffAccountEntity staff = staffRepository.save(new StaffAccountEntity(booth, "admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        authorization = "Bearer " + jwtProvider.issue(staff, Instant.now());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffRepository.deleteAll();
        boothRepository.deleteAll();
    }

    private String addTable() throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/tables").header("Authorization", authorization))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("label").asString();
    }

    private void bulkCreate(String json) throws Exception {
        mockMvc.perform(post("/api/v1/admin/tables/bulk").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
    }

    private int nextTableSeq() {
        return jdbcTemplate.queryForObject("select next_table_seq from booth where id = ?", Integer.class, booth.getId());
    }

    // ── H4: O2 라벨과의 충돌 ────────────────────────────

    @Test
    void skipsNumbersTakenByBulkCreatedAutoStyleLabels() throws Exception {
        bulkCreate("{\"count\":2,\"labelPrefix\":\"T\"}");   // T-1, T-2 — 자동 채번과 같은 모양

        assertEquals("T-3", addTable());   // main: "T-1" 유니크 위반 500
        assertEquals("T-4", addTable());
        assertEquals(5, nextTableSeq());
    }

    @Test
    void skipsNumbersWhoseNormalizedLabelAlreadyExists() throws Exception {
        bulkCreate("{\"labels\":[\"T1\",\"t 3\"]}");   // 정규화하면 T1·T3 — "T-1"·"T-3"과 같은 라벨로 취급된다(§1)

        assertEquals("T-2", addTable());
        assertEquals("T-4", addTable());   // T-3은 "t 3"과 겹쳐 건너뛴다
    }

    // ── H3: 카운터 되돌아감 자기치유 ────────────────────

    @Test
    void recoversWhenCounterWasRolledBackBehindExistingLabels() throws Exception {
        assertEquals("T-1", addTable());
        assertEquals("T-2", addTable());
        // O17 부스 설정 저장(전체 컬럼 UPDATE)이 카운터를 옛 값으로 되돌린 상황을 재현한다
        jdbcTemplate.update("update booth set next_table_seq = 1 where id = ?", booth.getId());

        assertEquals("T-3", addTable());   // main: 이후 모든 추가가 500
        assertEquals(4, nextTableSeq());
    }

    /**
     * 카운터가 기존 자동 라벨보다 뒤처져 있으면 빈 번호를 메우지 않고 최댓값 다음부터 잇는다 — 인쇄된 "T-7"이 있는 부스에
     * "T-1"을 새로 내면 번호 순서와 QR 인쇄 순서가 어긋난다. 정규화 건너뛰기만으로는 T-1이 나온다
     */
    @Test
    void continuesAfterHighestExistingAutoLabelInsteadOfFillingGaps() throws Exception {
        bulkCreate("{\"labels\":[\"T-7\"]}");

        assertEquals("T-8", addTable());
        assertEquals(9, nextTableSeq());
    }

    /** 카운터 갱신은 next_table_seq 컬럼만 써야 한다 — 채번 트랜잭션이 읽어 둔 옛 계좌·영업 여부로 O17 변경을 덮으면 안 된다 */
    @Test
    void numberingDoesNotOverwriteOtherBoothColumns() throws Exception {
        jdbcTemplate.update("update booth set bank_account = ?, is_open = false where id = ?", "새 계좌 9999", booth.getId());

        assertEquals("T-1", addTable());

        assertEquals("새 계좌 9999", jdbcTemplate.queryForObject("select bank_account from booth where id = ?", String.class, booth.getId()));
        assertEquals(Boolean.FALSE, jdbcTemplate.queryForObject("select is_open from booth where id = ?", Boolean.class, booth.getId()));
        assertEquals(2, nextTableSeq());
    }

    // ── 번호 반납은 그대로 ──────────────────────────────

    @Test
    void releasingLastUnusedNumberStillWorksWithColumnOnlyUpdate() throws Exception {
        assertEquals("T-1", addTable());
        String body = mockMvc.perform(post("/api/v1/admin/tables").header("Authorization", authorization))
                .andReturn().getResponse().getContentAsString();
        long secondId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(delete("/api/v1/admin/tables/{tableId}", secondId).header("Authorization", authorization))
                .andExpect(status().isNoContent());

        assertEquals(2, nextTableSeq());
        assertEquals("T-2", addTable());
    }

    // ── 동시 추가 ───────────────────────────────────────

    @Test
    void twentyConcurrentAddsProduceUniqueLabels() throws Exception {
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return tableAdminService.addSingleTable(authorization);
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            ready.await();
            start.countDown();
            List<String> labels = new ArrayList<>();
            for (Future<Object> future : futures) {
                Object result = future.get(30, TimeUnit.SECONDS);
                assertTrue(result instanceof TableAdminResponse, "동시 추가 중 예외: " + result);
                labels.add(((TableAdminResponse) result).label());
            }
            assertEquals(threads, labels.stream().distinct().count(), "라벨이 겹쳤다: " + labels);
            assertEquals(threads, tableRepository.findByBoothId(booth.getId()).size());
            assertEquals(threads + 1, nextTableSeq());
        } finally {
            executor.shutdownNow();
        }
    }
}
