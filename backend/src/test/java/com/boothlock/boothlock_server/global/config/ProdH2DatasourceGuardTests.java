package com.boothlock.boothlock_server.global.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-09-22 사고 재현 — RDS 주소가 남은 채 prod 프로필만으로 뜨면,
 * H2 드라이버 오류 대신 "무엇을 고치라"는 메시지로 멈춰야 한다.
 */
class ProdH2DatasourceGuardTests {

    @Test
    void H2_주소면_통과한다() {
        assertDoesNotThrow(() -> new ProdH2DatasourceGuard("jdbc:h2:file:/app/data/db/boothlock;MODE=MySQL"));
    }

    @Test
    void 주소가_없으면_통과한다_기본값을_쓴다() {
        assertDoesNotThrow(() -> new ProdH2DatasourceGuard(""));
        assertDoesNotThrow(() -> new ProdH2DatasourceGuard(null));
    }

    @Test
    void MySQL_주소인데_rds_프로필이_없으면_고칠_방법을_알려주며_멈춘다() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ProdH2DatasourceGuard(
                "jdbc:mysql://boothlock-db.ap-northeast-2.rds.amazonaws.com:3306/boothlock"));
        assertTrue(e.getMessage().contains("SPRING_PROFILES_ACTIVE=prod,rds"), e.getMessage());
        assertTrue(e.getMessage().contains("H2 전용이 아닙니다"), "이번 사고의 오판을 막는 문장이 있어야 한다");
    }

    @Test
    void 오류_메시지에_비밀번호가_새지_않는다() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ProdH2DatasourceGuard("jdbc:mysql://u:leak-me@h:3306/db?password=leak-too"));
        assertFalse(e.getMessage().contains("leak-me"));
        assertFalse(e.getMessage().contains("leak-too"));
    }
}
