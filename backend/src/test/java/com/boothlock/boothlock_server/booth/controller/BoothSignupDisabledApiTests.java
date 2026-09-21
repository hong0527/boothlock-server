package com.boothlock.boothlock_server.booth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code boothlock.signup.enabled=false}(기본값 = 운영 기본값)일 때의 동작 — 기능을 켜고 도는
 * BoothSignupApiTests와 짝을 이룬다.
 */
@SpringBootTest(properties = "boothlock.signup.enabled=false")
@AutoConfigureMockMvc
class BoothSignupDisabledApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void rejectsSignupWhenDisabled() throws Exception {
        String body = objectMapper.writeValueAsString(new SignupBody("새 부스", "new-admin", "password123"));

        mockMvc.perform(post("/api/v1/admin/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private record SignupBody(String boothName, String loginId, String password) {
    }
}
