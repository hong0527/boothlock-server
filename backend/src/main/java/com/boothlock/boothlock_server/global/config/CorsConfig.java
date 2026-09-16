package com.boothlock.boothlock_server.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 프론트와 API를 다른 도메인에 배포할 때 브라우저 fetch를 허용하는 CORS 매핑 (명세서 §1.1·§1.2).
 *
 * <p>spring-security가 없고 전역 필터도 없어 CORS는 Spring MVC의 핸들러 매핑 단계에서 처리한다.
 * 매핑이 있으면 preflight(OPTIONS)는 컨트롤러에 닿기 전에 200으로 끝난다 — 컨트롤러가
 * {@code @RequestHeader("Authorization")}을 필수로 걸어 두어도 preflight가 401·400이 되지 않는다.
 *
 * <p>허용 오리진은 {@code boothlock.cors.allowed-origins}(쉼표 구분)로 받는다.
 * <ul>
 *   <li>기본값은 로컬 Vite 개발 서버({@code http://localhost:5173})만. 운영 도메인은 환경 변수로 넣는다.</li>
 *   <li>{@code *}는 기동 거부 — 운영자·총관리자 API가 같은 서버에 열려 있어 아무 사이트에서나
 *       토큰만 있으면 호출할 수 있게 되고, 탈취한 토큰을 다른 오리진의 스크립트가 그대로 쓸 수 있다.
 *       Spring도 {@code allowCredentials=true}와 {@code *}의 조합은 거부하지만 여기는 credentials가
 *       false라 통과하므로 직접 막는다.</li>
 *   <li>빈 값이면 매핑을 등록하지 않는다 — 프론트를 같은 도메인에서 서빙하는 배포에서 CORS를 끄는 스위치.</li>
 *   <li>끝 슬래시·경로·공백이 섞인 값은 조용히 매칭에 실패해 "설정했는데 막힌다"로 나타나므로 기동 시 거부한다.</li>
 * </ul>
 *
 * <p>토큰은 전부 커스텀 헤더로 오가고 쿠키를 쓰지 않으므로(§1.2 sessionToken 규칙) {@code allowCredentials}는
 * false로 둔다. 쿠키가 없으니 CSRF 표면도 없고, 브라우저가 Origin 검사를 헤더 매칭으로만 수행한다.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** API 전체와 업로드 이미지(메뉴 사진·약도). 그 외 경로(h2-console·swagger)는 CORS 없이 같은 오리진에서만 */
    static final String[] PATH_PATTERNS = {"/api/**", "/uploads/**"};

    static final String[] ALLOWED_METHODS = {"GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"};

    /** 프론트가 실제로 보내는 헤더 — apiFetch(Authorization)·customerApiFetch(X-Session-Token)·주문 확정(Idempotency-Key) */
    static final String[] ALLOWED_HEADERS = {"Authorization", "X-Session-Token", "Idempotency-Key", "Content-Type"};

    /** 스크립트가 읽어야 하는 응답 헤더 — QR 다운로드 파일명(O4), 429 재시도 대기(§1.4) */
    static final String[] EXPOSED_HEADERS = {"Content-Disposition", "Retry-After"};

    /** preflight 결과 캐시. 헤더 목록이 바뀌는 일이 드물어 1시간으로 잡아 OPTIONS 왕복을 줄인다 */
    static final long MAX_AGE_SECONDS = 3600;

    /** scheme://host[:port] 만 허용. 경로·끝 슬래시·와일드카드는 Spring이 매칭하지 못하므로 애초에 받지 않는다 */
    private static final Pattern ORIGIN = Pattern.compile("^https?://[A-Za-z0-9.\\-]+(:\\d{1,5})?$");

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${boothlock.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.allowedOrigins = parseOrigins(allowedOrigins);
    }

    /**
     * 쉼표 구분 문자열을 오리진 목록으로 바꾼다. 빈 문자열이면 빈 목록(CORS 끔).
     * 하나라도 와일드카드거나 오리진 형식이 아니면 서버를 띄우지 않는다.
     */
    static List<String> parseOrigins(String raw) {
        List<String> origins = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return origins;
        }
        for (String piece : raw.split(",")) {
            String origin = piece.trim();
            if (origin.isEmpty()) {
                continue;
            }
            if (origin.contains("*")) {
                throw invalid(origin, "와일드카드는 허용하지 않습니다 — 운영자 API가 모든 사이트에 열립니다. 프론트 배포 도메인을 정확히 적을 것");
            }
            if (!ORIGIN.matcher(origin).matches()) {
                throw invalid(origin, "오리진은 scheme://host[:port] 형식이어야 합니다 (경로·끝 슬래시 없이, 예: https://boothlock.app)");
            }
            origins.add(origin);
        }
        return List.copyOf(origins);
    }

    private static IllegalArgumentException invalid(String origin, String reason) {
        return new IllegalArgumentException(
                "boothlock.cors.allowed-origins 설정이 잘못됐습니다 — " + reason + ". 문제 값: " + origin);
    }

    List<String> allowedOrigins() {
        return allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            return;
        }
        for (String pattern : PATH_PATTERNS) {
            CorsRegistration registration = registry.addMapping(pattern);
            registration.allowedOrigins(allowedOrigins.toArray(String[]::new))
                    .allowedMethods(ALLOWED_METHODS)
                    .allowedHeaders(ALLOWED_HEADERS)
                    .exposedHeaders(EXPOSED_HEADERS)
                    .allowCredentials(false)
                    .maxAge(MAX_AGE_SECONDS);
        }
    }
}
