package com.boothlock.boothlock_server.global.config;


/**
 * {@code prod}로 떴는데 {@code rds} 프로필 없이 H2가 아닌 주소가 들어오면, 알아보기 쉬운 이유로 기동을 멈춘다.
 *
 * <p><b>실제로 난 사고</b>(2026-09-22): RDS로 전환한 뒤 팀원이 배포 문서대로 재배포했는데,
 * 그 명령이 RDS 오버레이를 빼먹는 구조였다. 앱은 prod 프로필(H2 드라이버 고정)에 MySQL 주소를 받아
 * {@code Driver org.h2.Driver claims to not accept jdbcUrl, jdbc:mysql://...}로 기동에 실패했다.
 * 이 메시지만 보면 "코드가 H2 전용"으로 읽힌다 — 실제로 그렇게 판단하고 RDS를 끈 기록이 남아 있다.
 * 코드는 MySQL에서 전체 테스트를 통과한다. 문제는 설정이 반만 켜진 상태였다.
 *
 * <p>이제 DB 선택은 {@code .env.prod} 한 곳에서만 한다(docker-compose.prod.yml 주석 참고).
 * 이 가드는 그 중 반만 적은 경우 — 주소는 MySQL인데 프로필은 prod만 — 를 잡아 무엇을 고치면 되는지 알려준다.
 * 반대 경우(프로필은 rds인데 주소가 비었거나 H2)는 {@link RdsDatasourceGuard}가 잡는다.
 *
 * <p>이 클래스는 빈이 아니다. {@link DatasourceSettingsGuard}가 모든 빈보다 먼저 호출한다 —
 * 일반 빈으로 두면 DataSource 생성이 먼저 실패해 이 메시지가 나오지 않는다(2026-09-23 실측).
 */
public class ProdH2DatasourceGuard {

    public ProdH2DatasourceGuard(String url) {
        if (url != null && !url.isBlank() && !url.startsWith("jdbc:h2:")) {
            throw new IllegalStateException(
                    "H2가 아닌 DB 주소가 설정돼 있는데 rds 프로필이 꺼져 있습니다: " + RdsDatasourceGuard.maskCredentials(url)
                            + " — RDS를 쓰려면 .env.prod에 SPRING_PROFILES_ACTIVE=prod,rds 를 추가하세요. "
                            + "H2로 돌아가려면 .env.prod의 SPRING_DATASOURCE_* 세 줄을 지우세요. "
                            + "(코드는 H2 전용이 아닙니다 — 설정이 반만 켜진 상태입니다.)");
        }
    }
}
