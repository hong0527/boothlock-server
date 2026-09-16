package com.boothlock.boothlock_server.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 설정값 파싱과 기동 거부 규칙 — 컨텍스트 없이 생성자만 돌려 본다 */
class CorsConfigTests {

    @Test
    @DisplayName("기본값은 로컬 개발 서버 하나")
    void defaultIsLocalDevServerOnly() {
        assertThat(new CorsConfig("http://localhost:5173").allowedOrigins())
                .containsExactly("http://localhost:5173");
    }

    @Test
    @DisplayName("쉼표 구분 여러 오리진 — 공백과 빈 조각은 무시")
    void parsesMultipleOrigins() {
        assertThat(CorsConfig.parseOrigins(" https://boothlock.app , http://localhost:5173,, https://www.boothlock.app:8443 "))
                .containsExactly("https://boothlock.app", "http://localhost:5173", "https://www.boothlock.app:8443");
    }

    @Test
    @DisplayName("빈 값이면 오리진 없음 → CORS 매핑을 등록하지 않는다 (같은 오리진 배포)")
    void blankDisablesCors() {
        assertThat(CorsConfig.parseOrigins("")).isEmpty();
        assertThat(CorsConfig.parseOrigins("   ")).isEmpty();
        assertThat(CorsConfig.parseOrigins(null)).isEmpty();

        assertThat(registrationsOf(new CorsConfig(""))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*", "https://*.boothlock.app", "http://localhost:5173,*"})
    @DisplayName("와일드카드는 기동 거부 — 운영자 API가 모든 사이트에 열린다")
    void rejectsWildcard(String value) {
        assertThatThrownBy(() -> new CorsConfig(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boothlock.cors.allowed-origins")
                .hasMessageContaining("와일드카드");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://boothlock.app/",          // 끝 슬래시 — 브라우저 Origin 헤더에는 없어 매칭 실패
            "https://boothlock.app/app",       // 경로
            "boothlock.app",                   // scheme 없음
            "ftp://boothlock.app",             // 브라우저 오리진이 아닌 scheme
            "https://booth lock.app",          // 공백
            "https://boothlock.app:abc"        // 포트 형식 오류
    })
    @DisplayName("오리진 형식이 아니면 기동 거부 — 조용히 매칭에 실패하는 값을 미리 걸러낸다")
    void rejectsMalformedOrigin(String value) {
        assertThatThrownBy(() -> new CorsConfig(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme://host[:port]")
                .hasMessageContaining(value);
    }

    @Test
    @DisplayName("매핑은 /api/**·/uploads/** 두 경로에 같은 설정 — 메서드·헤더·노출 헤더·credentials false·1시간 캐시")
    void registersExpectedMappings() {
        Map<String, CorsConfiguration> registrations =
                registrationsOf(new CorsConfig("http://localhost:5173,https://boothlock.app"));

        assertThat(registrations).containsOnlyKeys("/api/**", "/uploads/**");
        registrations.values().forEach(config -> {
            assertThat(config.getAllowedOrigins()).containsExactly("http://localhost:5173", "https://boothlock.app");
            assertThat(config.getAllowedMethods()).containsExactlyInAnyOrder("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS");
            assertThat(config.getAllowedHeaders())
                    .containsExactlyInAnyOrder("Authorization", "X-Session-Token", "Idempotency-Key", "Content-Type");
            assertThat(config.getExposedHeaders()).containsExactlyInAnyOrder("Content-Disposition", "Retry-After");
            assertThat(config.getAllowCredentials()).isFalse();
            assertThat(config.getMaxAge()).isEqualTo(3600L);
        });
    }

    /** CorsRegistry의 결과는 protected — 테스트에서만 열어 본다 */
    private static Map<String, CorsConfiguration> registrationsOf(CorsConfig config) {
        var registry = new CorsRegistry() {
            Map<String, CorsConfiguration> configurations() {
                return getCorsConfigurations();
            }
        };
        config.addCorsMappings(registry);
        return registry.configurations();
    }
}
