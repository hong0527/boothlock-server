package com.boothlock.boothlock_server.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 빈 설정 = 같은 오리진 배포. 매핑이 없으니 Origin이 와도 CORS 헤더가 붙지 않고 거부도 하지 않는다 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "boothlock.cors.allowed-origins=")
class CorsDisabledMockMvcTests {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("빈 설정이면 실제 요청에 CORS 헤더가 없다 — 브라우저는 다른 오리진의 응답 읽기를 막는다")
    void actualRequestHasNoCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/event/booths").header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    @DisplayName("빈 설정이면 preflight도 CORS 허용 헤더 없이 지나간다")
    void preflightHasNoCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/orders")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-session-token"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
    }
}
