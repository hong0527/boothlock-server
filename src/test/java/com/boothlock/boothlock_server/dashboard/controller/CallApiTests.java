package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.dashboard.domain.CallReason;
import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CallApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StaffCallRepository staffCallRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired TableRepository tableRepository;
    @Autowired BoothRepository boothRepository;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        staffCallRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        boothRepository.deleteAll();

        BoothEntity booth = boothRepository.save(new BoothEntity("호출 부스", "은행 1234", null));
        TableEntity table = tableRepository.save(new TableEntity(booth, "A-1", "table-token-1"));
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, "session-token-1", LocalDateTime.now()));
        sessionId = session.getId();
    }

    @AfterEach
    void tearDown() {
        staffCallRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void createsCallAndReturnsCallId() throws Exception {
        mockMvc.perform(post("/api/v1/calls")
                        .param("sessionId", sessionId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.callId").exists())
                .andExpect(jsonPath("$.reason").value("HELP"));

        assertEquals(1, staffCallRepository.count());
        StaffCallEntity saved = staffCallRepository.findAll().get(0);
        assertEquals(sessionId, saved.getSession().getId());
        assertFalse(saved.isAcked());
    }

    @Test
    void rejectsRecallWithinThirtySeconds() throws Exception {
        mockMvc.perform(post("/api/v1/calls")
                        .param("sessionId", sessionId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/calls")
                        .param("sessionId", sessionId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"WATER\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("CALL_COOLDOWN"));

        assertEquals(1, staffCallRepository.count());
    }

    @Test
    void rejectsEndedSessionWithGone() throws Exception {
        TableSessionEntity session = tableSessionRepository.findById(sessionId).orElseThrow();
        session.end(LocalDateTime.now());
        tableSessionRepository.save(session);

        mockMvc.perform(post("/api/v1/calls")
                        .param("sessionId", sessionId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
    }

    @Test
    void rejectsUnknownSessionWithNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/calls")
                        .param("sessionId", "999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"HELP\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void acksCallAndIsIdempotent() throws Exception {
        TableSessionEntity session = tableSessionRepository.findById(sessionId).orElseThrow();
        StaffCallEntity call = staffCallRepository.save(
                new StaffCallEntity(session, CallReason.HELP, LocalDateTime.now()));

        mockMvc.perform(patch("/api/v1/admin/calls/{callId}/ack", call.getId()))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/admin/calls/{callId}/ack", call.getId()))
                .andExpect(status().isOk());

        assertTrue(staffCallRepository.findById(call.getId()).orElseThrow().isAcked());
    }

    @Test
    void acksUnknownCallReturnsNotFound() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/calls/{callId}/ack", 999999L))
                .andExpect(status().isNotFound());
    }
}
