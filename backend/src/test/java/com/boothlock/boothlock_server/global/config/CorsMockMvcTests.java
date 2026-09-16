package com.boothlock.boothlock_server.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프론트(다른 도메인)에서 오는 브라우저 요청이 실제 MVC 파이프라인에서 어떻게 처리되는지 확인한다.
 * 오리진을 두 개 설정해 목록 매칭과 비허용 오리진 거부를 함께 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "boothlock.cors.allowed-origins=http://localhost:5173, https://boothlock.app")
class CorsMockMvcTests {

    private static final String FRONT = "https://boothlock.app";
    private static final String LOCAL = "http://localhost:5173";
    private static final String EVIL = "https://evil.example";

    @Autowired private MockMvc mockMvc;

    // ── preflight ────────────────────────────────────────

    @Test
    @DisplayName("운영자 API preflight — Authorization 필수 컨트롤러에 닿지 않고 200, 헤더 4종·메서드·1시간 캐시")
    void preflightForAdminApiIsAnsweredBeforeController() throws Exception {
        mockMvc.perform(preflight("/api/v1/admin/booth", FRONT, "GET", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("PATCH")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("DELETE")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("authorization")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    @DisplayName("손님 주문 preflight — X-Session-Token·Idempotency-Key·Content-Type 세 헤더 모두 허용")
    void preflightForOrderCreateAllowsCustomerHeaders() throws Exception {
        mockMvc.perform(preflight("/api/v1/orders", LOCAL, "POST", "x-session-token, idempotency-key, content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, LOCAL))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("x-session-token")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("idempotency-key")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("content-type")))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    @DisplayName("업로드 이미지 preflight — /uploads/** 도 같은 매핑")
    void preflightForUploadsIsAllowed() throws Exception {
        mockMvc.perform(preflight("/uploads/event/x.png", FRONT, "GET", null))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT));
    }

    @Test
    @DisplayName("허용 목록에 없는 요청 헤더가 preflight에 오면 403 — 헤더 목록이 실제로 걸러낸다")
    void preflightWithUnknownHeaderIsRejected() throws Exception {
        mockMvc.perform(preflight("/api/v1/orders", FRONT, "POST", "x-forwarded-user"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("비허용 오리진 preflight는 403 (Spring DefaultCorsProcessor 기본 동작) — 허용 헤더 없음")
    void preflightFromUnknownOriginIsRejected() throws Exception {
        mockMvc.perform(preflight("/api/v1/orders", EVIL, "POST", "x-session-token"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("공개 축(/api/v1/event/**)도 같은 오리진 목록 — 홈 화면이 같은 프론트에서 열린다")
    void publicAxisUsesSameOriginList() throws Exception {
        mockMvc.perform(preflight("/api/v1/event/booths", LOCAL, "GET", null))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, LOCAL));
        mockMvc.perform(preflight("/api/v1/event/booths", EVIL, "GET", null))
                .andExpect(status().isForbidden());
    }

    // ── 실제 요청 ─────────────────────────────────────────

    @Test
    @DisplayName("실제 GET에 Allow-Origin은 요청 오리진 그대로(에코가 아니라 목록 매칭), Vary: Origin, credentials 없음")
    void actualRequestCarriesExactOrigin() throws Exception {
        mockMvc.perform(get("/api/v1/event/booths").header(HttpHeaders.ORIGIN, FRONT))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT))
                .andExpect(header().string(HttpHeaders.VARY, containsString("Origin")))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));

        mockMvc.perform(get("/api/v1/event/booths").header(HttpHeaders.ORIGIN, LOCAL))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, LOCAL));
    }

    @Test
    @DisplayName("노출 헤더 — 스크립트가 QR 파일명(Content-Disposition)과 Retry-After를 읽을 수 있다")
    void actualRequestExposesDownloadAndRetryHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/event/booths").header(HttpHeaders.ORIGIN, FRONT))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Content-Disposition")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Retry-After")));
    }

    @Test
    @DisplayName("비허용 오리진의 실제 요청도 403 — 컨트롤러에 닿지 않는다")
    void actualRequestFromUnknownOriginIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/event/booths").header(HttpHeaders.ORIGIN, EVIL))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("Origin 없는 요청(curl·같은 오리진)은 CORS 헤더 없이 그대로 처리")
    void requestWithoutOriginIsUntouched() throws Exception {
        mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("업로드 이미지 실제 GET — 없는 파일 404에도 Allow-Origin은 붙는다 (fetch로 존재 확인 가능)")
    void uploadsActualRequestCarriesOriginEvenOn404() throws Exception {
        mockMvc.perform(get("/uploads/event/no-such-map.png").header(HttpHeaders.ORIGIN, FRONT))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT));
    }

    // ── 기존 인증 동작 무변경 ─────────────────────────────

    @Test
    @DisplayName("인증 401·410은 그대로이고 에러 응답에도 Allow-Origin이 붙어 프론트가 에러 본문을 읽을 수 있다")
    void authErrorsAreUnchangedAndReadableCrossOrigin() throws Exception {
        mockMvc.perform(get("/api/v1/orders").header(HttpHeaders.ORIGIN, FRONT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT));

        mockMvc.perform(get("/api/v1/orders").header(HttpHeaders.ORIGIN, FRONT).header("X-Session-Token", "no-such-token"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT));

        mockMvc.perform(post("/api/v1/orders").header(HttpHeaders.ORIGIN, FRONT)
                        .header("Idempotency-Key", "idem-cors").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT));

        mockMvc.perform(get("/api/v1/admin/booth").header(HttpHeaders.ORIGIN, FRONT))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT));
    }

    // ── 범위 밖 ──────────────────────────────────────────

    @Test
    @DisplayName("/api·/uploads 밖(OpenAPI 문서)은 CORS 매핑이 없다")
    void pathsOutsideScopeHaveNoCors() throws Exception {
        mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.ORIGIN, FRONT))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("헤더 4종을 한 번에 요청한 preflight — 전부 허용 응답에 실린다")
    void allFourHeadersAllowedTogether() throws Exception {
        mockMvc.perform(preflight("/api/v1/orders", FRONT, "POST",
                        "authorization, x-session-token, idempotency-key, content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("authorization")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("x-session-token")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("idempotency-key")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("content-type")));
    }

    private static MockHttpServletRequestBuilder preflight(String path, String origin, String method, String requestHeaders) {
        MockHttpServletRequestBuilder builder = options(path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        if (requestHeaders != null) {
            builder.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders);
        }
        return builder;
    }
}
