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

    private String login(String loginId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Credentials(loginId, "password"))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private record Credentials(String loginId, String password) {
    }
}
