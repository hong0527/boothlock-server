package com.boothlock.boothlock_server.global.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * DB 설정이 반만 켜진 상태를 <b>어떤 빈보다도 먼저</b> 잡는다.
 *
 * <p>왜 BeanFactoryPostProcessor인가: 검사를 일반 빈 생성자에 두면, 설정이 어긋났을 때 DataSource·JPA가
 * 먼저 만들어지다 실패해서 {@code Driver org.h2.Driver claims to not accept jdbcUrl}처럼 원인을 알기 어려운
 * 메시지로 끝난다. 2026-09-22에 바로 그 메시지를 보고 "코드가 H2 전용"으로 판단해 RDS를 끈 일이 있었다.
 * BeanFactoryPostProcessor는 빈 정의만 읽힌 뒤, 어떤 싱글턴도 만들기 전에 실행된다.
 *
 * <ul>
 *   <li>rds 프로필 → {@link RdsDatasourceGuard}: 주소가 비었거나 MySQL이 아니면 중단</li>
 *   <li>prod 프로필(rds 없음) → {@link ProdH2DatasourceGuard}: 주소가 H2가 아니면 중단</li>
 * </ul>
 * 개발(bootRun)·테스트는 prod도 rds도 아니라 아무것도 하지 않는다.
 */
@Component
public class DatasourceSettingsGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        String url = resolveUrl();
        if (environment.acceptsProfiles(Profiles.of("rds"))) {
            new RdsDatasourceGuard(url);
        } else if (environment.acceptsProfiles(Profiles.of("prod"))) {
            new ProdH2DatasourceGuard(url);
        }
    }

    /**
     * {@code ${SPRING_DATASOURCE_URL}}처럼 값이 없는 자리표시자는 예외 대신 빈 값으로 본다 —
     * 그래야 "주소가 비었다"는 메시지로 떨어진다.
     */
    private String resolveUrl() {
        try {
            return environment.getProperty("spring.datasource.url", "");
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return "";
        }
    }
}
