package com.boothlock.boothlock_server.global.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 빈 주소로 조용히 뜨는 것을 막는다.
 *
 * 2026-09-21 운영 전환에서 실제로 났던 사고다 — compose 치환 실수로 주소가 빈 값이 됐는데
 * 서버가 그냥 떠서 인메모리 H2에 붙었다. API는 200인데 부스가 0개였고 에러는 없었다.
 */
class RdsDatasourceGuardTests {

    @Test
    void 주소가_비면_기동을_막는다() {
        for (String blank : new String[] { "", "   ", null }) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new RdsDatasourceGuard(blank));
            assertTrue(e.getMessage().contains("SPRING_DATASOURCE_URL"),
                    "무엇을 확인해야 하는지 메시지에 있어야 한다: " + e.getMessage());
        }
    }

    @Test
    void H2_주소면_기동을_막는다() {
        // rds 프로필인데 H2로 붙는 것은 설정이 반쯤 적용된 상태다 — 그대로 두면 빈 DB로 서비스한다
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new RdsDatasourceGuard("jdbc:h2:file:/app/data/db/boothlock;MODE=MySQL"));
        assertTrue(e.getMessage().contains("MySQL 주소가 아닙니다"));
    }

    @Test
    void MySQL_주소면_통과한다() {
        assertDoesNotThrow(() -> new RdsDatasourceGuard(
                "jdbc:mysql://boothlock-db.ap-northeast-2.rds.amazonaws.com:3306/boothlock?serverTimezone=Asia/Seoul"));
    }

    @Test
    void 오류_메시지에_비밀번호가_새지_않는다() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new RdsDatasourceGuard("jdbc:postgresql://user:s3cret-pw@host:5432/db?password=another-secret"));
        assertFalse(e.getMessage().contains("s3cret-pw"), "URL에 박힌 자격증명이 그대로 나오면 안 된다: " + e.getMessage());
        assertFalse(e.getMessage().contains("another-secret"), "쿼리스트링 비밀번호가 그대로 나오면 안 된다: " + e.getMessage());
        assertTrue(e.getMessage().contains("***"));
    }
}
