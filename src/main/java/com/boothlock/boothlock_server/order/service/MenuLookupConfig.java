package com.boothlock.boothlock_server.order.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MenuLookup 구현이 아직 없을 때만 임시 구현을 등록한다.
 * ConditionalOnMissingBean은 @Bean 메서드에서만 동작한다 — @Component에 붙이면 스캔 순서에 따라 빈이 아예 등록되지 않는다.
 * 다만 일반 @Configuration에서는 이 조건이 스캔 순서에 의존하므로 완전한 보장은 아니다.
 * TODO(메뉴 파트 머지 시): 이 클래스와 PendingMenuLookup을 반드시 함께 삭제할 것.
 */
@Configuration
public class MenuLookupConfig {

    @Bean
    @ConditionalOnMissingBean(MenuLookup.class)
    public MenuLookup pendingMenuLookup() {
        return new PendingMenuLookup();
    }
}
