package com.boothlock.boothlock_server.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDS 오버레이 프로필(`application-rds.properties`)의 값 고정.
 *
 * 이 두 값은 잘못 바뀌어도 로컬(H2)에서는 아무 증상이 없고, RDS에 올린 뒤에야 문제가 드러난다.
 * 그것도 에러가 아니라 조용한 데이터 손상이나 인증 우회 형태라, 사람이 알아채기 어렵다.
 * 그래서 파일 내용을 테스트로 묶어 둔다.
 *
 * <ul>
 *   <li>{@code ddl-auto=validate} — update면 Hibernate가 {@code COLLATE utf8mb4_bin}을 모른 채
 *       토큰 3개 컬럼을 대소문자 무시로 만든다. 틀린 토큰으로 인증이 통과한다(실측).</li>
 *   <li>{@code transaction-isolation=READ_COMMITTED} — InnoDB 기본값(REPEATABLE READ)이면
 *       동시성 테스트 5건이 실제로 실패한다. 두 운영자가 서로 다른 항목을 동시에 취소하면
 *       상대의 취소를 못 보고 합계가 옛값으로 남는다. 예외도 안 난다.</li>
 * </ul>
 *
 * 파일이 실제로 읽히는지(프로필 이름 ↔ 파일명 규칙)는 여기서 검증하지 못한다.
 * 그건 실제 MySQL로 `scripts/verify-rds-profile.sh`를 돌려야 한다.
 */
class RdsProfilePropertiesTests {

    private static Properties load(String name) throws IOException {
        Properties props = new Properties();
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }

    @Test
    void 스키마를_자동으로_바꾸지_않는다() throws IOException {
        assertEquals("validate", load("application-rds.properties").getProperty("spring.jpa.hibernate.ddl-auto"),
                "RDS에서 update로 두면 토큰 컬럼이 대소문자 무시로 만들어져 인증이 뚫린다");
    }

    @Test
    void 격리수준을_READ_COMMITTED로_고정한다() throws IOException {
        assertEquals("TRANSACTION_READ_COMMITTED",
                load("application-rds.properties").getProperty("spring.datasource.hikari.transaction-isolation"),
                "REPEATABLE READ면 동시 항목취소에서 합계가 옛값으로 남는다");
    }

    @Test
    void 접속정보를_파일에_박지_않는다() throws IOException {
        Properties rds = load("application-rds.properties");
        for (String key : new String[] {
                "spring.datasource.url", "spring.datasource.username", "spring.datasource.password" }) {
            String value = rds.getProperty(key);
            assertTrue(value != null && value.startsWith("${") && value.endsWith("}"),
                    key + " 는 환경변수 자리표시자여야 한다 — 엔드포인트는 바뀌고 비밀번호는 커밋하면 안 된다. 실제 값: " + value);
        }
    }

    /** 기본값 0(무한)이면 응답 없이 끊긴 소켓에 요청 스레드가 영원히 묶여 풀이 마른다 — URL이 아니라 이 파일이 건다 */
    @Test
    void 드라이버_네트워크_타임아웃을_건다() throws IOException {
        Properties rds = load("application-rds.properties");
        assertEquals("5000", rds.getProperty("spring.datasource.hikari.data-source-properties.connectTimeout"));
        assertEquals("30000", rds.getProperty("spring.datasource.hikari.data-source-properties.socketTimeout"));
    }

    @Test
    void MySQL_드라이버를_쓴다() throws IOException {
        assertEquals("com.mysql.cj.jdbc.Driver",
                load("application-rds.properties").getProperty("spring.datasource.driver-class-name"));
    }

    @Test
    void 운영_기본값은_H2_그대로다() throws IOException {
        // rds 프로필을 켜지 않으면 지금 배포가 바뀌면 안 된다 — 오버레이 설계의 전제다
        Properties prod = load("application-prod.properties");
        assertTrue(prod.getProperty("spring.datasource.url").contains("jdbc:h2:file:"),
                "prod 프로필은 H2를 그대로 써야 한다. RDS 전환은 rds 프로필을 얹어서 한다");
        assertEquals("org.h2.Driver", prod.getProperty("spring.datasource.driver-class-name"));
    }

    @Test
    void 두_프로필이_같은_격리수준을_쓴다() throws IOException {
        // H2(READ COMMITTED 기본)와 MySQL(REPEATABLE READ 기본)의 차이를 앱에서 없앤다 —
        // 그래야 H2에서 통과한 동시성 테스트가 MySQL에서도 같은 의미를 갖는다
        assertEquals(load("application-prod.properties").getProperty("spring.datasource.hikari.transaction-isolation"),
                load("application-rds.properties").getProperty("spring.datasource.hikari.transaction-isolation"));
    }

    @Test
    void 평문_비밀번호가_들어있지_않다() throws IOException {
        for (String file : new String[] { "application-rds.properties", "application-prod.properties" }) {
            Properties props = load(file);
            String password = props.getProperty("spring.datasource.password");
            // 비어 있거나(H2) 자리표시자여야 한다. 실제 값이 박히면 커밋에 비밀번호가 남는다
            assertFalse(password != null && !password.isBlank() && !password.startsWith("${"),
                    file + " 에 평문 비밀번호가 들어 있다");
        }
    }
}
