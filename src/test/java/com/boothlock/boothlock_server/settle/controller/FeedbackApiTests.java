package com.boothlock.boothlock_server.settle.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.settle.domain.FeedbackEntity;
import com.boothlock.boothlock_server.settle.repository.FeedbackRepository;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FeedbackApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FeedbackRepository feedbackRepository;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired BoothRepository boothRepository;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long boothId;
    private Long staffId;
    private String token;
    private StaffAccountEntity staff;

    @BeforeEach
    void setUp() throws Exception {
        feedbackRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();

        BoothEntity booth = boothRepository.save(new BoothEntity("피드백 부스", "은행 1234", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("correct-password");
        staff = staffAccountRepository.save(new StaffAccountEntity(
                booth, "feedback-admin", hash,
                LocalDateTime.of(2026, 8, 29, 12, 0), StaffRole.ADMIN));
        boothId = booth.getId();
        staffId = staff.getId();
        token = login();
    }

    @AfterEach
    void tearDown() {
        feedbackRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void savesValidFeedbackWithAuthenticatedBoothAndStaff() throws Exception {
        String response = submit(new FeedbackBody(5, true, false, true, "다시 사용하고 싶습니다."))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long feedbackId = Long.valueOf(response);
        FeedbackEntity saved = feedbackRepository.findById(feedbackId).orElseThrow();
        assertEquals(boothId, saved.getBoothId());
        assertEquals(staffId, saved.getStaffId());
        assertEquals(5, saved.getRating());
        assertEquals(true, saved.getEasySetup());
        assertEquals(false, saved.getEasyOrders());
        assertEquals(true, saved.getWouldReuse());
        assertEquals("다시 사용하고 싶습니다.", saved.getComment());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void acceptsRatingBoundaryValues() throws Exception {
        submit(new FeedbackBody(1, true, true, true, null)).andExpect(status().isCreated());
        submit(new FeedbackBody(5, true, true, true, null)).andExpect(status().isCreated());
        assertEquals(2, feedbackRepository.count());
    }

    @Test
    void rejectsRatingOutsideOneToFive() throws Exception {
        submit(new FeedbackBody(0, true, true, true, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        submit(new FeedbackBody(6, true, true, true, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertEquals(0, feedbackRepository.count());
    }

    @Test
    void rejectsMissingRequiredBooleans() throws Exception {
        submit(new FeedbackBody(3, null, true, true, null)).andExpect(status().isBadRequest());
        submit(new FeedbackBody(3, true, null, true, null)).andExpect(status().isBadRequest());
        submit(new FeedbackBody(3, true, true, null, null)).andExpect(status().isBadRequest());
        assertEquals(0, feedbackRepository.count());
    }

    @Test
    void rejectsCommentLongerThanOneThousandCharacters() throws Exception {
        submit(new FeedbackBody(3, true, true, true, "가".repeat(1001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertEquals(0, feedbackRepository.count());
    }

    @Test
    void rejectsRequestWithoutAuthorizationHeader() throws Exception {
        mockMvc.perform(post("/api/v1/admin/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validFeedback())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertEquals(0, feedbackRepository.count());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String expiredToken = jwtProvider.issue(staff, Instant.now().minusSeconds(13 * 60 * 60));

        submitWithToken(expiredToken, validFeedback())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertEquals(0, feedbackRepository.count());
    }

    @Test
    void rejectsInactiveAccount() throws Exception {
        jdbcTemplate.update("update staff_account set active = false where id = ?", staffId);

        submitWithToken(token, validFeedback())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertEquals(0, feedbackRepository.count());
    }

    @Test
    void rejectsTokenIssuedBeforePasswordChange() throws Exception {
        jdbcTemplate.update(
                "update staff_account set password_changed_at = ? where id = ?",
                LocalDateTime.of(2026, 8, 30, 12, 0), staffId);

        submitWithToken(token, validFeedback())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertEquals(0, feedbackRepository.count());
    }

    private org.springframework.test.web.servlet.ResultActions submit(FeedbackBody body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/feedback")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions submitWithToken(
            String accessToken, FeedbackBody body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/feedback")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private FeedbackBody validFeedback() {
        return new FeedbackBody(5, true, true, true, "인증 테스트");
    }

    private String login() throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new Credentials("feedback-admin", "correct-password"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private record FeedbackBody(
            Integer rating,
            Boolean easySetup,
            Boolean easyOrders,
            Boolean wouldReuse,
            String comment) {
    }

    private record Credentials(String loginId, String password) {
    }
}
