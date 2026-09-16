package com.boothlock.boothlock_server.booth.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothAccountChangeLogRepository;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothWebhookNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    /** 커밋 후 웹훅이 몇 번 불렸는지 세려고 가짜로 바꾼다 — 실제 전송 로직은 BoothWebhookNotifierTests가 본다 */
    @MockitoBean BoothWebhookNotifier webhookNotifier;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from booth_table");
        logRepository.deleteAll(); staffRepository.deleteAll(); boothRepository.deleteAll();
        BoothEntity booth = boothRepository.save(new BoothEntity("기존 부스", "기존 계좌", "10:00~20:00"));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staffRepository.save(new StaffAccountEntity(booth, "admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        staffRepository.save(new StaffAccountEntity(booth, "staff", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.STAFF));
    }

    /** 이 클래스는 @Transactional이 아니라 실제로 커밋된다 — 남긴 행이 다른 테스트의 deleteAll을 FK로 막는다 */
    @AfterEach
    void tearDown() {
        logRepository.deleteAll(); staffRepository.deleteAll(); boothRepository.deleteAll();
        jdbc.update("delete from booth_table");
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
        // 같은 값 재전송은 변경이 아니다 — 웹훅도 첫 변경 1회뿐
        verify(webhookNotifier, times(1)).notifyBankAccountChanged(eq(boothId()), eq("admin"), any());
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

    /** booth_id가 NULL인 계정 — 널 검사가 없으면 여기서 NPE로 500이 난다 */
    @Test
    void rejectsStaffWithoutBooth() throws Exception {
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        staffRepository.save(new StaffAccountEntity(null, "nobooth", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        mockMvc.perform(patch("/api/v1/admin/booth").header("Authorization", "Bearer " + login("nobooth"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isOpen\":false}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /** 인증이 본문 검증보다 먼저여야 한다 — 아니면 잘못된 토큰에도 400과 검증 규칙이 새어 나간다 */
    @Test
    void checksTokenBeforeBody() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/booth").header("Authorization", "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsEmptyPatch() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/booth").header("Authorization", "Bearer " + login("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    // ── v0.5 신설: category·mapX·mapY ─────────────────────

    @Test
    void getReturnsHomeInfoFieldsAsNullBeforeSeeding() throws Exception {
        mockMvc.perform(get("/api/v1/admin/booth").header("Authorization", "Bearer " + login("staff")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.mapX").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.mapY").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.category").hasJsonPath())
                .andExpect(jsonPath("$.mapX").hasJsonPath())
                .andExpect(jsonPath("$.mapY").hasJsonPath());
    }

    /** STAFF도 바꿀 수 있고, 계좌가 아니므로 감사 로그·웹훅이 생기지 않는다 */
    @Test
    void staffChangesCategoryAndPinWithoutAuditOrWebhook() throws Exception {
        String token = login("staff");
        patchBooth(token, "{\"category\":\"CAFE\",\"mapX\":0,\"mapY\":10000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("CAFE"))
                .andExpect(jsonPath("$.mapX").value(0))
                .andExpect(jsonPath("$.mapY").value(10000))
                .andExpect(jsonPath("$.bankAccount").value("기존 계좌"));
        mockMvc.perform(get("/api/v1/admin/booth").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("CAFE"))
                .andExpect(jsonPath("$.mapX").value(0))
                .andExpect(jsonPath("$.mapY").value(10000));
        BoothEntity booth = reloadBooth();
        assertEquals("CAFE", booth.getCategory());
        assertEquals(0, booth.getMapX());
        assertEquals(10000, booth.getMapY());

        // ADMIN이 보내도 계좌가 없으면 감사·웹훅 없음
        patchBooth(login("admin"), "{\"category\":\"ETC\",\"mapX\":10000,\"mapY\":0}").andExpect(status().isOk());
        assertEquals(0, logRepository.count());
        verify(webhookNotifier, never()).notifyBankAccountChanged(anyLong(), anyString(), any());
    }

    @Test
    void acceptsEveryCategoryValue() throws Exception {
        String token = login("staff");
        for (String category : new String[]{"FOOD", "CAFE", "GOODS", "ETC"}) {
            patchBooth(token, "{\"category\":\"" + category + "\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.category").value(category));
        }
    }

    /** 대문자 정확 일치만 — 소문자·공백 섞인 값을 고쳐 저장하지 않는다. null 지우기도 받지 않는다 */
    @Test
    void rejectsInvalidCategory() throws Exception {
        String token = login("admin");
        patchBooth(token, "{\"category\":\"FOOD\"}").andExpect(status().isOk());
        String[] bodies = {
                "{\"category\":\"food\"}", "{\"category\":\"Food\"}", "{\"category\":\" FOOD\"}",
                "{\"category\":\"FOOD \"}", "{\"category\":\"DRINK\"}", "{\"category\":\"\"}",
                "{\"category\":null}", "{\"category\":1}", "{\"category\":[\"FOOD\"]}"};
        for (String body : bodies) {
            patchBooth(token, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertEquals("FOOD", reloadBooth().getCategory());
    }

    @Test
    void rejectsCoordinatesOutOfRangeOrNotInteger() throws Exception {
        String token = login("staff");
        patchBooth(token, "{\"mapX\":3200,\"mapY\":5400}").andExpect(status().isOk());
        String[] bodies = {
                "{\"mapX\":-1,\"mapY\":0}", "{\"mapX\":10001,\"mapY\":0}",
                "{\"mapX\":0,\"mapY\":-1}", "{\"mapX\":0,\"mapY\":10001}",
                "{\"mapX\":3200.5,\"mapY\":100}", "{\"mapX\":3200.0,\"mapY\":100}",
                "{\"mapX\":1e3,\"mapY\":100}", "{\"mapX\":\"3200\",\"mapY\":100}",
                "{\"mapX\":true,\"mapY\":100}", "{\"mapX\":99999999999,\"mapY\":100}",
                "{\"mapX\":-4294967296,\"mapY\":100}",
                "{\"mapX\":null,\"mapY\":null}", "{\"mapX\":{},\"mapY\":100}"};
        for (String body : bodies) {
            patchBooth(token, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        BoothEntity booth = reloadBooth();
        assertEquals(3200, booth.getMapX());
        assertEquals(5400, booth.getMapY());
    }

    /** 반쪽 좌표 — 한쪽만 오면 저장된 다른 쪽과 섞지 않고 400 */
    @Test
    void rejectsHalfCoordinate() throws Exception {
        String token = login("staff");
        patchBooth(token, "{\"mapX\":3200,\"mapY\":5400}").andExpect(status().isOk());
        for (String body : new String[]{"{\"mapX\":100}", "{\"mapY\":100}",
                "{\"mapX\":100,\"mapY\":null}", "{\"mapX\":null,\"mapY\":100}",
                "{\"mapX\":100,\"name\":\"새 부스\"}"}) {
            // 메시지까지 본다 — 반쪽 검사가 빠져도 null 좌표 검사가 400을 내므로 상태 코드만으론 구별이 안 된다
            patchBooth(token, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.message").value(body.contains("null")
                            ? org.hamcrest.Matchers.containsString("정수")
                            : org.hamcrest.Matchers.containsString("함께")));
        }
        BoothEntity booth = reloadBooth();
        assertEquals(3200, booth.getMapX());
        assertEquals(5400, booth.getMapY());
        assertEquals("기존 부스", booth.getName());
    }

    /** 부분 수정 — 보낸 필드만 바뀐다 (category만 보내면 좌표는 그대로, 좌표만 보내면 category는 그대로) */
    @Test
    void changesOnlyFieldsThatWereSent() throws Exception {
        String token = login("staff");
        patchBooth(token, "{\"category\":\"GOODS\",\"mapX\":1,\"mapY\":2}").andExpect(status().isOk());
        patchBooth(token, "{\"category\":\"FOOD\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.mapX").value(1)).andExpect(jsonPath("$.mapY").value(2));
        patchBooth(token, "{\"mapX\":7,\"mapY\":8}").andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("FOOD"));
        patchBooth(token, "{\"isOpen\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("FOOD"))
                .andExpect(jsonPath("$.mapX").value(7)).andExpect(jsonPath("$.mapY").value(8));
        BoothEntity booth = reloadBooth();
        assertEquals("FOOD", booth.getCategory());
        assertEquals(7, booth.getMapX());
        assertEquals(8, booth.getMapY());
        assertEquals("기존 부스", booth.getName());
        assertEquals("10:00~20:00", booth.getOperatingHours());
    }

    @Test
    void keepsRejectingUnknownFields() throws Exception {
        String token = login("admin");
        for (String body : new String[]{"{\"mapx\":1,\"mapy\":2}", "{\"Category\":\"FOOD\"}",
                "{\"category\":\"FOOD\",\"tableCount\":3}", "{\"id\":1}"}) {
            patchBooth(token, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertNull(reloadBooth().getCategory());
    }

    /** 요청 하나는 전부 반영되거나 전부 안 된다 — 뒤쪽 필드에서 403이면 앞에서 바꾼 category도 롤백 */
    @Test
    void staffWithBankAccountIsForbiddenAndNothingChanges() throws Exception {
        patchBooth(login("staff"), "{\"category\":\"GOODS\",\"mapX\":1,\"mapY\":2,\"bankAccount\":\"새 계좌\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        BoothEntity booth = reloadBooth();
        assertNull(booth.getCategory());
        assertNull(booth.getMapX());
        assertEquals("기존 계좌", booth.getBankAccount());
        assertEquals(0, logRepository.count());
        verify(webhookNotifier, never()).notifyBankAccountChanged(anyLong(), anyString(), any());
    }

    @Test
    void adminChangesAccountTogetherWithHomeInfo() throws Exception {
        patchBooth(login("admin"), "{\"category\":\"CAFE\",\"mapX\":5,\"mapY\":6,\"bankAccount\":\"새 계좌\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccount").value("새 계좌"))
                .andExpect(jsonPath("$.category").value("CAFE"));
        assertEquals(1, logRepository.count());
        verify(webhookNotifier, times(1)).notifyBankAccountChanged(eq(boothId()), eq("admin"), any());
    }

    private org.springframework.test.web.servlet.ResultActions patchBooth(String token, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/admin/booth").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private BoothEntity reloadBooth() {
        return boothRepository.findAll().getFirst();
    }

    private Long boothId() {
        return reloadBooth().getId();
    }

    private String login(String id) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Credentials(id, "password"))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
    private record Credentials(String loginId, String password) {}
}
