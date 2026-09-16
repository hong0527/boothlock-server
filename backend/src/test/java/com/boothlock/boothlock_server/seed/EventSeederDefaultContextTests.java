package com.boothlock.boothlock_server.seed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 평범한 테스트 컨텍스트(BoothlockServerApplicationTests와 같은 구성)에는 시더가 없다 —
 * 기본값이 꺼짐이어서, 다른 테스트가 시드 데이터에 오염되지 않는다.
 */
@SpringBootTest
class EventSeederDefaultContextTests {

    @Autowired ApplicationContext context;

    @Test
    void seederIsAbsentByDefault() {
        assertThat(context.getBeanProvider(EventSeeder.class).getIfAvailable()).isNull();
        assertThat(context.getEnvironment().getProperty("boothlock.seed.enabled")).isNull();
    }
}
