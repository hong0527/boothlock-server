package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O22 배치 좌표 입력 검증 (명세서 O22, §7-20).
 * 숫자는 0~10000 안에서 반올림해 받고(운영자 프론트가 드래그 좌표를 반올림하지 않고 보낸다 — TableGridCard),
 * 음수·상한 초과·문자열·불리언·배열·null·반쪽은 400이며 저장된 좌표는 그대로다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TablePositionValidationTests {

    @Autowired MockMvc mockMvc;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;

    private String authorization;
    private TableEntity table;

    @BeforeEach
    void setUp() {
        cleanUp();
        BoothEntity booth = boothRepository.save(new BoothEntity("좌표 부스", "은행 1234", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        StaffAccountEntity staff = staffRepository.save(new StaffAccountEntity(booth, "admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        authorization = "Bearer " + jwtProvider.issue(staff, Instant.now());
        table = new TableEntity(booth, "A-1", "tok-A-1");
        table.updatePosition(7, 8);
        table = tableRepository.save(table);
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

    @ParameterizedTest(name = "{0} → posX={1}, posY={2}")
    @CsvSource(delimiter = '|', quoteCharacter = '\'', value = {
            "{\"posX\":0,\"posY\":0}                 | 0     | 0",
            "{\"posX\":10000,\"posY\":10000}         | 10000 | 10000",
            "{\"posX\":120.4,\"posY\":240.6}         | 120   | 241",      // 소수는 반올림
            "{\"posX\":120.5,\"posY\":0.5}           | 121   | 1",        // .5는 올림(HALF_UP)
            "{\"posX\":9999.6,\"posY\":10000.4}      | 10000 | 10000",    // 반올림 뒤 상한 안이면 수용
            "{\"posX\":1e2,\"posY\":2.4e2}           | 100   | 240",      // 지수 표기도 숫자
            "{\"posX\":120.0,\"posY\":240.000}       | 120   | 240",
            "{\"posX\":33,\"posY\":44.49}            | 33    | 44",
    })
    void acceptsNumbersWithinRangeRoundingDecimals(String body, int expectedX, int expectedY) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", table.getId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posX").value(expectedX))
                .andExpect(jsonPath("$.posY").value(expectedY));

        TableEntity reloaded = tableRepository.findById(table.getId()).orElseThrow();
        assertEquals(expectedX, reloaded.getPosX());
        assertEquals(expectedY, reloaded.getPosY());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"posX\":120}",
            "{\"posY\":240}",
            "{\"posX\":-1,\"posY\":240}",
            "{\"posX\":120,\"posY\":-1}",
            "{\"posX\":-0.4,\"posY\":240}",           // 반올림하면 0이지만 음수 입력 자체를 거부한다
            "{\"posX\":10000.5,\"posY\":240}",        // 반올림하면 10001 — 상한 초과
            "{\"posX\":10001,\"posY\":240}",
            "{\"posX\":120,\"posY\":10001}",
            "{\"posX\":null,\"posY\":240}",
            "{\"posX\":120,\"posY\":null}",
            "{\"posX\":\"120\",\"posY\":240}",         // 문자열은 숫자처럼 보여도 거부
            "{\"posX\":120,\"posY\":\"240\"}",
            "{\"posX\":true,\"posY\":240}",
            "{\"posX\":[120],\"posY\":240}",
            "{\"posX\":{\"v\":1},\"posY\":240}",
            "{\"posX\":2147483648,\"posY\":240}",
            "{\"posX\":99999999999999999999,\"posY\":240}",
            "{\"posX\":1e400,\"posY\":240}",
            "null",
            "[]",
            "\"posX\"",
            "{\"posX\":120,",
            ""
    })
    void rejectsInvalidBodiesWithBadRequestAndKeepsPosition(String body) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", table.getId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").exists());

        TableEntity reloaded = tableRepository.findById(table.getId()).orElseThrow();
        assertEquals(7, reloaded.getPosX());
        assertEquals(8, reloaded.getPosY());
    }

    /** 운영자 프론트가 실제로 보내는 형태 — 드래그 좌표(clientX 차이)는 소수일 수 있다 (TableGridCard.tsx handlePointerMove) */
    @Test
    void acceptsFrontendDragCoordinatesWithFractions() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", table.getId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"posX\":448.66668701171875,\"posY\":263.3333282470703}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posX").value(449))
                .andExpect(jsonPath("$.posY").value(263));
    }
}
