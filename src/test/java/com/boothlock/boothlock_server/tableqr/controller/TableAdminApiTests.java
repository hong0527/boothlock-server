package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TableAdminApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;

    private BoothEntity booth;
    private TableEntity table;

    @BeforeEach
    void setUp() {
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
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void regeneratesTokenAndKeepsActiveSession() throws Exception {
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, "session-token-1", LocalDateTime.now()));

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

    private String login(String loginId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Credentials(loginId, "password"))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private record Credentials(String loginId, String password) {
    }
}
