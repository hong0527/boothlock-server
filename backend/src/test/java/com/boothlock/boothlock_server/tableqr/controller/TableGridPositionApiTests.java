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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O22b 테이블 그리드 좌표 저장 (명세서 밖, 파일럿 전용) — 운영자가 숫자로 직접 입력하는 행/열.
 * O22(posX/posY, px)와는 별개 필드/엔드포인트다. 검증 스타일은 TablePositionValidationTests를 참고했다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TableGridPositionApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;

    private String authorization;
    private BoothEntity booth;
    private TableEntity table;
    private TableEntity other;
    private TableEntity otherBoothTable;

    @BeforeEach
    void setUp() {
        cleanUp();
        booth = boothRepository.save(new BoothEntity("그리드 부스", "은행 1234", null));
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        StaffAccountEntity staff = staffRepository.save(new StaffAccountEntity(booth, "admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        authorization = "Bearer " + jwtProvider.issue(staff, Instant.now());
        table = tableRepository.save(new TableEntity(booth, "A-1", "tok-A-1"));
        other = tableRepository.save(new TableEntity(booth, "A-2", "tok-A-2"));
        otherBoothTable = tableRepository.save(new TableEntity(otherBooth, "Z-1", "tok-Z-1"));
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

    @Test
    void savesGridPositionAndListsItOnO3Too() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/grid-position", table.getId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"row\":3,\"col\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(table.getId()))
                .andExpect(jsonPath("$.gridRow").value(3))
                .andExpect(jsonPath("$.gridCol").value(5))
                // posX/posY(드래그, 별개 개념)는 이 저장으로 건드리지 않는다
                .andExpect(jsonPath("$.posX").value(nullValue()))
                .andExpect(jsonPath("$.posY").value(nullValue()));

        TableEntity reloaded = tableRepository.findById(table.getId()).orElseThrow();
        assertEquals(3, reloaded.getGridRow());
        assertEquals(5, reloaded.getGridCol());
        assertNull(reloaded.getPosX());

        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", authorization))
                .andExpect(jsonPath("$.tables[0].gridRow").value(3))
                .andExpect(jsonPath("$.tables[0].gridCol").value(5));
    }

    @Test
    void unassignsByPassingBothNull() throws Exception {
        table.updateGridPosition(3, 5);
        table = tableRepository.save(table);

        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/grid-position", table.getId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"row\":null,\"col\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gridRow").value(nullValue()))
                .andExpect(jsonPath("$.gridCol").value(nullValue()));

        TableEntity reloaded = tableRepository.findById(table.getId()).orElseThrow();
        assertNull(reloaded.getGridRow());
        assertNull(reloaded.getGridCol());
    }

    /** "{}"는 row/col 둘 다 null과 같다(Jackson 기본 역직렬화) — 이미 미배치인 테이블에 빈 바디를 보내도 400이 아니라 200(무변화)이어야 한다 */
    @Test
    void emptyBodyIsValidNoopUnassign() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/grid-position", table.getId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gridRow").value(nullValue()))
                .andExpect(jsonPath("$.gridCol").value(nullValue()));
    }

    @Test
    void rejectsDuplicateCoordinateWithinSameBooth() throws Exception {
        other.updateGridPosition(3, 5);
        tableRepository.save(other);

        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/grid-position", table.getId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"row\":3,\"col\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertNull(tableRepository.findById(table.getId()).orElseThrow().getGridRow());
    }

    @Test
    void allowsSameCoordinateAcrossDifferentBooths() throws Exception {
        otherBoothTable.updateGridPosition(3, 5);
        tableRepository.save(otherBoothTable);

        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/grid-position", table.getId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"row\":3,\"col\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gridRow").value(3))
                .andExpect(jsonPath("$.gridCol").value(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"row\":3}",
            "{\"col\":5}",
            "{\"row\":0,\"col\":5}",
            "{\"row\":3,\"col\":0}",
            "{\"row\":51,\"col\":5}",
            "{\"row\":3,\"col\":51}",
            "{\"row\":-1,\"col\":5}",
            "{\"row\":\"3\",\"col\":5}",
            "{\"row\":3.5,\"col\":5}",
    })
    void rejectsInvalidBodiesWithBadRequestAndKeepsPosition(String body) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/grid-position", table.getId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertNull(tableRepository.findById(table.getId()).orElseThrow().getGridRow());
    }

    @Test
    void hidesOtherBoothsTableAsNotFound() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/grid-position", otherBoothTable.getId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"row\":1,\"col\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertNull(tableRepository.findById(otherBoothTable.getId()).orElseThrow().getGridRow());
    }

    @Test
    void requiresValidOperatorToken() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/grid-position", table.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"row\":1,\"col\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
