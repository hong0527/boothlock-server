package com.boothlock.boothlock_server.seed;

import com.boothlock.boothlock_server.BoothlockServerApplication;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.event.repository.EventMapRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 실제 기동 경로로 본다 — SpringApplication.run이 ApplicationRunner를 부르는지, 실패하면 기동이 멈추는지,
 * 두 번 띄워도 같은 DB에 중복이 생기지 않는지.
 * 테스트마다 임시 폴더의 전용 파일 DB를 써서 다른 테스트와 데이터를 섞지 않는다.
 */
class EventSeederStartupTests {

    private static final String SEED = """
            {
              "booths": [
                {"name": "기동 테스트 주점", "bankAccount": "계좌 1", "category": "FOOD", "mapX": 10, "mapY": 20,
                 "admin": {"loginId": "startup-food", "password": "startup-password-1"}},
                {"name": "기동 테스트 굿즈", "bankAccount": "계좌 2", "category": "GOODS", "mapX": 30, "mapY": 40,
                 "admin": {"loginId": "startup-goods", "password": "startup-password-2"}}
              ],
              "eventMap": {"imageUrl": "/uploads/event/startup.png", "width": 100, "height": 50}
            }
            """;

    /**
     * 테스트 전용 파일 DB — 메모리 DB는 컨텍스트를 닫을 때 H2가 함께 내려가 비워지므로(실측)
     * "껐다 다시 켜기"를 재현할 수 없다. 임시 폴더에 두어 테스트가 끝나면 사라진다.
     */
    private static String dbUrl(Path dir) {
        return "jdbc:h2:file:" + dir.resolve("db-" + UUID.randomUUID()).toAbsolutePath() + ";MODE=MySQL";
    }

    /**
     * 명령행 인자로 넘긴다 — SpringApplicationBuilder.properties()는 우선순위가 가장 낮은 기본값이라
     * 테스트 application.properties의 공유 메모리 DB 주소에 덮여 버린다(실측).
     */
    private static ConfigurableApplicationContext start(String dbUrl, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + dbUrl,
                "--spring.jpa.show-sql=false",
                "--boothlock.jwt.secret=test-only-jwt-secret-at-least-32-bytes"));
        for (String property : extra) args.add("--" + property);
        return new SpringApplicationBuilder(BoothlockServerApplication.class)
                .web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    /** 약도 파일을 둔 업로드 폴더 — 시더는 imageUrl이 가리키는 파일이 실제로 있어야 기동한다 */
    private static String eventDir(Path dir, boolean withImage) throws Exception {
        // 약도 폴더 가드(EventUploadWebConfig)가 마지막 폴더 이름을 event로 요구한다
        Path uploads = Files.createDirectories(dir.resolve("uploads").resolve("event"));
        if (withImage) Files.write(uploads.resolve("startup.png"), new byte[]{1});
        return "boothlock.upload.event-dir=" + uploads;
    }

    private static long count(String dbUrl, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(dbUrl, "sa", ""); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("두 번 기동해도 부스·계정·약도가 한 벌만 있다")
    void startingTwiceDoesNotDuplicate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("seed.json");
        Files.writeString(file, SEED, StandardCharsets.UTF_8);
        String url = dbUrl(dir);
        for (int run = 0; run < 2; run++) {
            try (ConfigurableApplicationContext context = start(url,
                    "boothlock.seed.enabled=true", "boothlock.seed.file=" + file, eventDir(dir, true))) {
                // 공유 테스트 DB가 아니라 이 테스트의 파일 DB에 붙었는지부터 확인
                assertThat(context.getEnvironment().getProperty("spring.datasource.url")).isEqualTo(url);
                assertThat(context.getBean(BoothRepository.class).count()).isEqualTo(2);
                assertThat(context.getBean(StaffAccountRepository.class).count()).isEqualTo(2);
                assertThat(context.getBean(EventMapRepository.class).count()).isEqualTo(1);
            }
        }
        assertThat(count(url, "select count(*) from booth")).isEqualTo(2);
        assertThat(count(url, "select count(*) from staff_account where role = 'ADMIN'")).isEqualTo(2);
        assertThat(count(url, "select count(*) from event_map")).isEqualTo(1);
    }

    @Test
    @DisplayName("입력이 틀리면 기동 자체가 실패하고 원인이 메시지에 있다")
    void invalidSeedFailsStartupWithClearMessage(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("seed.json");
        Files.writeString(file, SEED.replace("\"mapX\": 30", "\"mapX\": 10001"), StandardCharsets.UTF_8);
        String url = dbUrl(dir);
        assertThatThrownBy(() -> start(url, "boothlock.seed.enabled=true", "boothlock.seed.file=" + file, eventDir(dir, true)))
                .rootCause()
                .isInstanceOf(EventSeedException.class)
                .hasMessageContaining("booths[1].mapX");
        // 첫 부스는 올바랐지만 들어가지 않았다 — 검증이 DB 반영보다 먼저다
        assertThat(count(url, "select count(*) from booth")).isZero();
    }

    @Test
    @DisplayName("DB 반영 중 실패하면 기동 실패 + 전부 롤백")
    void failureDuringApplyRollsBackEverything(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("seed.json");
        Files.writeString(file, SEED, StandardCharsets.UTF_8);
        String url = dbUrl(dir);
        // 시더 없이 한 번 띄워 스키마를 만들고, 두 번째 부스의 loginId를 무소속 계정으로 선점해 둔다
        start(url).close();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.executeUpdate("insert into staff_account (login_id, password_hash, password_changed_at, role, active, failed_login_count) "
                    + "values ('startup-goods', '{noop}x', '2026-09-01 12:00:00', 'ADMIN', true, 0)");
        }

        assertThatThrownBy(() -> start(url, "boothlock.seed.enabled=true", "boothlock.seed.file=" + file, eventDir(dir, true)))
                .hasStackTraceContaining("행사 시딩 실패")
                .hasStackTraceContaining("startup-goods");

        assertThat(count(url, "select count(*) from booth")).isZero();
        assertThat(count(url, "select count(*) from event_map")).isZero();
        assertThat(count(url, "select count(*) from staff_account")).isEqualTo(1);
    }

    @Test
    @DisplayName("약도 이미지 파일이 없으면 기동 실패 — E2는 200인데 이미지만 404로 깨지는 상태를 막는다")
    void missingMapImageFailsStartup(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("seed.json");
        Files.writeString(file, SEED, StandardCharsets.UTF_8);
        String url = dbUrl(dir);
        assertThatThrownBy(() -> start(url, "boothlock.seed.enabled=true", "boothlock.seed.file=" + file,
                eventDir(dir, false)))
                .rootCause()
                .isInstanceOf(EventSeedException.class)
                .hasMessageContaining("약도 이미지 파일이 없습니다")
                .hasMessageContaining("startup.png");
        assertThat(count(url, "select count(*) from booth")).isZero();
    }

    @Test
    @DisplayName("한 번 시딩한 뒤 약도 파일이 사라지면 재기동도 실패한다")
    void restartFailsWhenMapImageDisappears(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("seed.json");
        Files.writeString(file, SEED, StandardCharsets.UTF_8);
        String url = dbUrl(dir);
        String uploads = eventDir(dir, true);
        start(url, "boothlock.seed.enabled=true", "boothlock.seed.file=" + file, uploads).close();
        Files.delete(dir.resolve("uploads").resolve("event").resolve("startup.png"));

        assertThatThrownBy(() -> start(url, "boothlock.seed.enabled=true", "boothlock.seed.file=" + file, uploads))
                .rootCause().hasMessageContaining("약도 이미지 파일이 없습니다");
    }

    @Test
    @DisplayName("enabled=true인데 파일 경로가 없으면 기동 실패")
    void enabledWithoutFileFailsStartup(@TempDir Path dir) {
        String url = dbUrl(dir);
        assertThatThrownBy(() -> start(url, "boothlock.seed.enabled=true"))
                .rootCause().hasMessageContaining("boothlock.seed.file");
    }

    @Test
    @DisplayName("존재하지 않는 파일이면 기동 실패")
    void missingFileFailsStartup(@TempDir Path dir) {
        String url = dbUrl(dir);
        assertThatThrownBy(() -> start(url, "boothlock.seed.enabled=true",
                "boothlock.seed.file=" + dir.resolve("nope.json")))
                .rootCause().hasMessageContaining("시딩 파일이 없습니다");
    }

    @Test
    @DisplayName("enabled를 끄면 올바른 파일이 있어도 아무것도 넣지 않는다")
    void disabledSeedDoesNothingEvenWithValidFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("seed.json");
        Files.writeString(file, SEED, StandardCharsets.UTF_8);
        String url = dbUrl(dir);
        try (ConfigurableApplicationContext context = start(url,
                "boothlock.seed.enabled=false", "boothlock.seed.file=" + file)) {
            assertThat(context.getBeanProvider(EventSeeder.class).getIfAvailable()).isNull();
        }
        // 설정 키를 아예 안 쓴 경우(기본값)도 동일
        try (ConfigurableApplicationContext context = start(url, "boothlock.seed.file=" + file)) {
            assertThat(context.getBeanProvider(EventSeeder.class).getIfAvailable()).isNull();
        }
        assertThat(count(url, "select count(*) from booth")).isZero();
    }

    @Test
    @DisplayName("빈 등록 조건 — true일 때만 생긴다")
    void beanExistsOnlyWhenEnabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(BoothRepository.class, () -> mock(BoothRepository.class))
                .withBean(StaffAccountRepository.class, () -> mock(StaffAccountRepository.class))
                .withBean(EventMapRepository.class, () -> mock(EventMapRepository.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withUserConfiguration(EventSeeder.class);

        runner.run(context -> assertThat(context).doesNotHaveBean(EventSeeder.class));
        runner.withPropertyValues("boothlock.seed.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(EventSeeder.class));
        runner.withPropertyValues("boothlock.seed.enabled=yes")
                .run(context -> assertThat(context).doesNotHaveBean(EventSeeder.class));
        runner.withPropertyValues("boothlock.seed.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(EventSeeder.class));
    }
}
