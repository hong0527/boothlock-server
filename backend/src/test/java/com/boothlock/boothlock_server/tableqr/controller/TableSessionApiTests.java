package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableStatus;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionCreateRequest;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.service.TableSessionService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TableSessionApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired BoothRepository boothRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired TableSessionService tableSessionService;

    private String tableToken;
    private Long tableId;

    @BeforeEach
    void setUp() {
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        boothRepository.deleteAll();

        BoothEntity booth = boothRepository.save(new BoothEntity("세션 부스", "은행 1234", null));
        TableEntity table = tableRepository.save(new TableEntity(booth, "A-1", "table-token-1"));
        tableToken = table.getTableToken();
        tableId = table.getId();
    }

    @AfterEach
    void tearDown() {
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void issuesNewSessionAndOccupiesTable() throws Exception {
        mockMvc.perform(post("/api/v1/table-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"" + tableToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").exists())
                .andExpect(jsonPath("$.booth.name").value("세션 부스"))
                .andExpect(jsonPath("$.booth.isOpen").value(true))
                .andExpect(jsonPath("$.table.label").value("A-1"))
                .andExpect(jsonPath("$.restored").value(false));

        assertEquals(1, tableSessionRepository.count());
        assertEquals(TableStatus.OCCUPIED, tableRepository.findById(tableId).orElseThrow().getStatus());
    }

    @Test
    void restoresActiveSessionInstead() throws Exception {
        TableEntity table = tableRepository.findById(tableId).orElseThrow();
        TableSessionEntity existing = tableSessionRepository.save(
                new TableSessionEntity(table, "existing-session-token", LocalDateTime.now().minusMinutes(5)));

        mockMvc.perform(post("/api/v1/table-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"" + tableToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").value("existing-session-token"))
                .andExpect(jsonPath("$.restored").value(true));

        assertEquals(1, tableSessionRepository.count());
        TableSessionEntity reloaded = tableSessionRepository.findById(existing.getId()).orElseThrow();
        assertTrue(reloaded.getLastActivityAt().isAfter(existing.getStartedAt()));
    }

    @Test
    void rejectsInvalidTableTokenWithNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/table-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"no-such-token\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsBlankTableTokenWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/table-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void concurrentScansCreateOnlyOneActiveSession() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Boolean> scan = () -> {
            ready.countDown();
            start.await();
            return tableSessionService.createOrRestore(new TableSessionCreateRequest(tableToken)).restored();
        };

        List<Future<Boolean>> futures = List.of(executor.submit(scan), executor.submit(scan));
        ready.await();
        start.countDown();
        List<Boolean> restoredFlags = futures.stream()
                .map(f -> {
                    try {
                        return f.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());
        executor.shutdown();

        assertEquals(1, restoredFlags.stream().filter(restored -> !restored).count());
        assertEquals(1, restoredFlags.stream().filter(restored -> restored).count());
        assertEquals(1, tableSessionRepository.count());
    }
}
