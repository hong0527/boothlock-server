package com.boothlock.boothlock_server.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * {@code rds} 프로필로 떴는데 접속 주소가 비어 있으면 기동을 멈춘다.
 *
 * <p><b>실제로 난 사고</b>(2026-09-21): 운영 전환 때 주소가 빈 값으로 전달됐는데 서버가 그냥 떴다.
 * Hibernate가 URL 없이 인메모리 H2로 붙어서, API는 200을 주는데 부스 목록이 0개였다.
 * 에러도 경고도 없었다. 축제 중에 이러면 "주문이 전부 사라졌다"로 보이고 원인을 찾기 어렵다.
 *
 * <p>빈 값으로 뜨느니 아예 안 뜨는 게 낫다. 안 뜨면 이전 컨테이너가 살아 있거나 로그에 이유가 남는다.
 *
 * <p>값이 전달되지 않는 흔한 이유는 compose 변수 치환이다. {@code environment:}에 쓴
 * {@code ${VAR}}는 {@code env_file}을 보지 않고 셸이나 {@code .env}만 본다. 그래서
 * {@code docker-compose.rds.yml}은 {@code environment:}로 데이터소스를 넘기지 않고
 * {@code env_file}이 전달한 값을 그대로 쓴다.
 */
@Configuration
@Profile("rds")
public class RdsDatasourceGuard {

    public RdsDatasourceGuard(@Value("${spring.datasource.url:}") String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "rds 프로필인데 spring.datasource.url이 비어 있습니다. "
                            + ".env.prod의 SPRING_DATASOURCE_URL을 확인하세요. "
                            + "값이 있는데도 비어 보이면 compose가 그 파일을 안 읽은 것입니다 "
                            + "(environment: ${VAR} 치환은 env_file을 보지 않습니다).");
        }
        if (!url.startsWith("jdbc:mysql:")) {
            throw new IllegalStateException(
                    "rds 프로필인데 MySQL 주소가 아닙니다: " + maskCredentials(url)
                            + " — H2로 돌리려면 rds 프로필을 빼고 SPRING_DATASOURCE_* 환경변수도 지우세요. "
                            + "환경변수가 남아 있으면 프로필을 되돌려도 계속 그 주소로 붙습니다.");
        }
    }

    /** 오류 메시지에 비밀번호가 섞여 나가지 않게 — URL에 자격증명을 박는 형식도 있다 */
    private static String maskCredentials(String url) {
        return url.replaceAll("://[^@/]*@", "://***@").replaceAll("(?i)password=[^&]*", "password=***");
    }
}
