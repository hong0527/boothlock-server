package com.boothlock.boothlock_server.global.config;

import com.boothlock.boothlock_server.BoothlockServerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 기동 순서에서 가드 메시지가 <b>DataSource 오류보다 먼저</b> 나오는지 본다.
 *
 * <p>가드 생성자만 불러 보는 단위 테스트로는 이걸 확인할 수 없다 — 실제로 2026-09-23에 가드를 일반 빈으로
 * 두었더니 단위 테스트는 통과했는데, 도커로 띄우면 {@code Driver org.h2.Driver claims to not accept jdbcUrl}이
 * 먼저 나와 가드가 아예 돌지 않았다.
 *
 * <p>DB에 실제로 붙지 않는다 — 가드가 모든 빈보다 먼저 멈추므로 MySQL이 없어도 된다.
 */
class DatasourceSettingsGuardStartupTests {

    private static final String MYSQL_URL = "jdbc:mysql://127.0.0.1:1/boothlock";

    private static Throwable startupFailure(String... args) {
        RuntimeException e = assertThrows(RuntimeException.class, () -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(BoothlockServerApplication.class)
                    .run(args)) {
                // 떴다면 가드가 안 돈 것
            }
        });
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root;
    }

    @Test
    void 주소는_MySQL인데_rds_프로필이_없으면_가드_메시지로_멈춘다_9월22일_사고() {
        Throwable root = startupFailure("--spring.profiles.active=prod", "--spring.datasource.url=" + MYSQL_URL);
        assertTrue(root instanceof IllegalStateException, "가드가 먼저 멈춰야 한다. 실제: " + root);
        assertTrue(root.getMessage().contains("SPRING_PROFILES_ACTIVE=prod,rds"), root.getMessage());
    }

    @Test
    void rds_프로필인데_주소가_없으면_가드_메시지로_멈춘다() {
        Throwable root = startupFailure("--spring.profiles.active=prod,rds");
        assertTrue(root instanceof IllegalStateException, "가드가 먼저 멈춰야 한다. 실제: " + root);
        assertTrue(root.getMessage().contains("SPRING_DATASOURCE_URL"), root.getMessage());
    }
}
