package com.boothlock.boothlock_server.booth.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 임시 데모 회원가입 (명세서 밖 — BoothSignupService 상단 주석 참고).
 * 기능이 켜진 상태를 가정하므로 여기서만 켠다 — 전역(test/resources/application.properties)에서 켜면
 * 모든 테스트가 운영 기본값(꺼짐)과 다른 설정으로 돌게 된다. 꺼진 상태는 BoothSignupDisabledApiTests가 본다.
 */
@SpringBootTest(properties = "boothlock.signup.enabled=true")
@AutoConfigureMockMvc
class BoothSignupApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired BoothRepository boothRepository;

    @BeforeEach
    void setUp() {
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void createsBoothAndAdminAccountThenReturnsWorkingJwt() throws Exception {
        mockMvc.perform(signup("새 부스", "new-admin", "password123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(43_200))
                .andExpect(jsonPath("$.staff.role").value("ADMIN"))
                .andExpect(jsonPath("$.staff.boothName").value("새 부스"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        StaffAccountEntity saved = staffAccountRepository.findByLoginId("new-admin").orElseThrow();
        assertEquals(StaffRole.ADMIN, saved.getRole());
        // 지연 로딩 프록시(saved.getBooth())는 트랜잭션 밖이라 못 읽는다 — 이름으로 직접 조회
        BoothEntity booth = boothRepository.findByName("새 부스").getFirst();
        assertEquals("계좌 미입력 - 로그인 후 설정에서 등록", booth.getBankAccount());   // AccountPage.tsx가 이 문구로 미등록을 판단한다
        assertTrue(PasswordEncoderFactories.createDelegatingPasswordEncoder()
                .matches("password123", saved.getPasswordHash()));   // 평문 저장 아님
    }

    @Test
    void rejectsDuplicateLoginIdAndDoesNotLeaveOrphanBooth() throws Exception {
        BoothEntity existingBooth = boothRepository.save(new BoothEntity("기존 부스", "테스트 계좌", null));
        staffAccountRepository.save(new StaffAccountEntity(
                existingBooth, "taken-id",
                PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("x12345678"),
                LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.ADMIN));

        mockMvc.perform(signup("또 다른 부스", "taken-id", "password123"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertEquals(1, boothRepository.count());   // 실패한 가입이 만든 부스가 남아있지 않다
    }

    @Test
    void rejectsBlankBoothName() throws Exception {
        mockMvc.perform(signup("  ", "some-id", "password123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsShortPassword() throws Exception {
        mockMvc.perform(signup("새 부스", "some-id", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void issuedTokenLogsIntoTheNewBooth() throws Exception {
        String body = mockMvc.perform(signup("새 부스", "new-admin", "password123"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String token = objectMapper.readTree(body).get("accessToken").asText();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/admin/booth").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 부스"));
    }

    private MockHttpServletRequestBuilder signup(String boothName, String loginId, String password) throws Exception {
        return post("/api/v1/admin/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SignupBody(boothName, loginId, password)));
    }

    private record SignupBody(String boothName, String loginId, String password) {
    }
}
