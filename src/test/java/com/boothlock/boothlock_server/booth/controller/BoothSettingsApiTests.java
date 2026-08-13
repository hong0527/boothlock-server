package com.boothlock.boothlock_server.booth.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothAccountChangeLogRepository;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BoothSettingsApiTests {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired BoothAccountChangeLogRepository logRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("create table if not exists booth_table (id bigint primary key, booth_id bigint not null)");
        jdbc.update("delete from booth_table");
        logRepository.deleteAll(); staffRepository.deleteAll(); boothRepository.deleteAll();
        BoothEntity booth = boothRepository.save(new BoothEntity("기존 부스", "기존 계좌", "10:00~20:00"));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staffRepository.save(new StaffAccountEntity(booth, "admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        staffRepository.save(new StaffAccountEntity(booth, "staff", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.STAFF));
    }

    @Test
    void adminChangesAccountAndServerCreatesOneAuditLog() throws Exception {
        LocalDateTime before = LocalDateTime.now();
        String token = login("admin");
        mockMvc.perform(patch("/api/v1/admin/booth").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankAccount\":\"새 계좌\",\"name\":\"새 부스\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bankAccount").value("새 계좌"));
        var logs = logRepository.findAll();
        assertEquals(1, logs.size());
        assertEquals("기존 계좌", logs.getFirst().getOldValue());
        assertEquals("새 계좌", logs.getFirst().getNewValue());
        assertFalse(logs.getFirst().getChangedAt().isBefore(before));
        mockMvc.perform(patch("/api/v1/admin/booth").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bankAccount\":\"새 계좌\"}"))
                .andExpect(status().isOk());
        assertEquals(1, logRepository.count());
    }

    @Test
    void staffCanChangeOpenButCannotChangeAccount() throws Exception {
        String token = login("staff");
        mockMvc.perform(patch("/api/v1/admin/booth").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isOpen\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isOpen").value(false));
        mockMvc.perform(patch("/api/v1/admin/booth").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bankAccount\":\"새 계좌\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void rejectsEmptyPatch() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/booth").header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private String login(String id) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Credentials(id, "password"))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
    private record Credentials(String loginId, String password) {}
}
