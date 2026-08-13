package com.boothlock.boothlock_server.booth.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BoothLoginApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired BoothRepository boothRepository;

    @BeforeEach
    void setUp() {
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
        BoothEntity booth = boothRepository.save(new BoothEntity("테스트 부스", "테스트 계좌", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("correct-password");
        staffAccountRepository.save(new StaffAccountEntity(
                booth, "test-admin", hash, LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
    }

    @AfterEach
    void tearDown() {
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void issuesTwelveHourJwtWithRequiredClaims() throws Exception {
        String body = mockMvc.perform(login("test-admin", "correct-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(43_200))
                .andExpect(jsonPath("$.staff.role").value("ADMIN"))
                .andExpect(jsonPath("$.staff.boothName").value("테스트 부스"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String token = objectMapper.readTree(body).get("accessToken").asText();
        String claimsJson = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        JsonNode claims = objectMapper.readTree(claimsJson);
        assertEquals("ADMIN", claims.get("role").asText());
        assertEquals(claims.get("staffId").asLong(), claims.get("sub").asLong());
        assertEquals(43_200, claims.get("exp").asLong() - claims.get("iat").asLong());
    }

    @Test
    void locksOnFifthFailureAndDoesNotIncreaseWhileLocked() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(login("test-admin", "wrong-password"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"));
        }
        mockMvc.perform(login("test-admin", "wrong-password"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("LOGIN_LOCKED"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").value(30));
        mockMvc.perform(login("test-admin", "correct-password"))
                .andExpect(status().isTooManyRequests());

        StaffAccountEntity saved = staffAccountRepository.findByLoginId("test-admin").orElseThrow();
        assertEquals(5, saved.getFailedLoginCount());
    }

    @Test
    void rejectsMissingCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            String loginId, String password) throws Exception {
        return post("/api/v1/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new Credentials(loginId, password)));
    }

    private record Credentials(String loginId, String password) {
    }
}
