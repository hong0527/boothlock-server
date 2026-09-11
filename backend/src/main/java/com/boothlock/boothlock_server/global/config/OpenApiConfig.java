package com.boothlock.boothlock_server.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI(/swagger-ui.html) 문서 상단 정보 — 구현 기준은 항상 팀 노션 API 명세서 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI boothlockOpenApi() {
        return new OpenAPI().info(new Info()
                .title("부스락 BoothLock API")
                .version("v0.4.2")
                .description("축제 부스 QR 테이블오더 API. 구현 기준 명세서는 팀 노션."));
    }
}
