package com.boothlock.boothlock_server.seed;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.event.repository.EventMapRepository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시더를 켠 컨텍스트 — 기동 시 들어간 데이터를 손님 홈 화면(E1·E2)과 로그인(O1)으로 실제 확인한다.
 *
 * <p>테스트 DB(메모리 H2)는 다른 테스트 클래스와 공유되므로, 이 클래스가 만든 행은 이름·loginId로 골라 지운다.
 * 첫 테스트는 "기동 때 이미 들어가 있다"를 봐야 해서 순서를 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventSeederIntegrationTests {

    static final String FOOD_NAME = "시드 테스트 주점";
    static final String CAFE_NAME = "시드 테스트 카페";
    static final String FOOD_LOGIN = "seed-it-food-admin";
    static final String CAFE_LOGIN = "seed-it-cafe-admin";
    static final String FOOD_PASSWORD = "seed-food-password-1";
    static final String CAFE_PASSWORD = "seed-cafe-password-2";
    static final String MAP_URL = "/uploads/event/seed-it-map.png";

    static String seedJson(String cafeLoginId) {
        return """
                {
                  "booths": [
                    {"name": "%s", "bankAccount": "카카오뱅크 3333-01-0000001 (시드)", "category": "FOOD",
                     "mapX": 3200, "mapY": 5400, "operatingHours": "18:00~02:00",
                     "admin": {"loginId": "%s", "passwordEnv": "SEED_IT_FOOD_PASSWORD"}},
                    {"name": "%s", "bankAccount": "국민 9999-00-0000002 (시드)", "category": "CAFE",
                     "mapX": 0, "mapY": 10000,
                     "admin": {"loginId": "%s", "password": "%s"}}
                  ],
                  "eventMap": {"imageUrl": "%s", "width": 1600, "height": 1200}
                }
                """.formatted(FOOD_NAME, FOOD_LOGIN, CAFE_NAME, cafeLoginId, CAFE_PASSWORD, MAP_URL);
    }

    @DynamicPropertySource
    static void seedProperties(DynamicPropertyRegistry registry) throws Exception {
        Path file = Files.createTempFile("event-seed-it", ".json");
        file.toFile().deleteOnExit();
        Files.writeString(file, seedJson(CAFE_LOGIN), StandardCharsets.UTF_8);
        registry.add("boothlock.seed.enabled", () -> "true");
        registry.add("boothlock.seed.file", file::toString);
        // 시더는 약도 파일이 실제로 있어야 기동한다 — 테스트 전용 약도 폴더에 빈 파일을 둔다
        // 약도 폴더 가드(EventUploadWebConfig)가 마지막 폴더 이름을 event로 요구한다
        Path base = Files.createTempDirectory("event-seed-it-uploads");
        Path eventDir = Files.createDirectories(base.resolve("event"));
        Files.write(eventDir.resolve("seed-it-map.png"), new byte[]{1});
        base.toFile().deleteOnExit();
        eventDir.toFile().deleteOnExit();
        eventDir.resolve("seed-it-map.png").toFile().deleteOnExit();
        registry.add("boothlock.upload.event-dir", eventDir::toString);
        // passwordEnv는 Spring Environment로 찾는다 — OS 환경 변수와 같은 경로라 테스트에선 속성으로 넣는다
        registry.add("SEED_IT_FOOD_PASSWORD", () -> FOOD_PASSWORD);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EventSeeder seeder;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired EventMapRepository eventMapRepository;
    @Autowired JdbcTemplate jdbc;

    @AfterAll
    void cleanUp() {
        deleteSeededRows();
    }

    @Test
    @Order(1)
    void startupSeedIsVisibleThroughHomeScreenAndLogin() throws Exception {
        // E1 — 다른 클래스가 남긴 부스가 있을 수 있어 이름으로 골라 본다
        mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booths[?(@.name == '" + FOOD_NAME + "')].category", contains("FOOD")))
                .andExpect(jsonPath("$.booths[?(@.name == '" + FOOD_NAME + "')].mapX", contains(3200)))
                .andExpect(jsonPath("$.booths[?(@.name == '" + FOOD_NAME + "')].mapY", contains(5400)))
                .andExpect(jsonPath("$.booths[?(@.name == '" + FOOD_NAME + "')].isOpen", contains(true)))
                .andExpect(jsonPath("$.booths[?(@.name == '" + CAFE_NAME + "')].category", contains("CAFE")))
                .andExpect(jsonPath("$.booths[?(@.name == '" + CAFE_NAME + "')].mapX", contains(0)))
                .andExpect(jsonPath("$.booths[?(@.name == '" + CAFE_NAME + "')].mapY", contains(10000)));
        mockMvc.perform(get("/api/v1/event/booths").param("category", "CAFE"))
                .andExpect(jsonPath("$.booths[?(@.name == '" + CAFE_NAME + "')].name", contains(CAFE_NAME)));

        // E2
        mockMvc.perform(get("/api/v1/event/map"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(MAP_URL))
                .andExpect(jsonPath("$.width").value(1600))
                .andExpect(jsonPath("$.height").value(1200));

        // O1 — 환경 변수 경로와 파일 평문 경로 둘 다 기존 로그인과 같은 해시로 들어갔는지
        String foodToken = login(FOOD_LOGIN, FOOD_PASSWORD, FOOD_NAME);
        login(CAFE_LOGIN, CAFE_PASSWORD, CAFE_NAME);
        mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + FOOD_LOGIN + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        // O16 — 시드 ADMIN 토큰으로 자기 부스 설정이 보인다
        mockMvc.perform(get("/api/v1/admin/booth").header("Authorization", "Bearer " + foodToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(FOOD_NAME))
                .andExpect(jsonPath("$.bankAccount").value("카카오뱅크 3333-01-0000001 (시드)"))
                .andExpect(jsonPath("$.operatingHours").value("18:00~02:00"))
                .andExpect(jsonPath("$.category").value("FOOD"));

        StaffAccountEntity account = staffAccountRepository.findByLoginId(FOOD_LOGIN).orElseThrow();
        assertThat(account.getRole()).isEqualTo(StaffRole.ADMIN);
        assertThat(account.getPasswordHash()).startsWith("{bcrypt}").doesNotContain(FOOD_PASSWORD);
    }

    @Test
    @Order(2)
    void secondRunCreatesNothing() {
        long booths = boothRepository.count();
        long staff = staffAccountRepository.count();
        long maps = eventMapRepository.count();

        EventSeeder.Result result = seeder.seed();

        assertThat(result).isEqualTo(new EventSeeder.Result(0, 2, 0, 2, false));
        assertThat(boothRepository.count()).isEqualTo(booths);
        assertThat(staffAccountRepository.count()).isEqualTo(staff);
        assertThat(eventMapRepository.count()).isEqualTo(maps);
        assertThat(boothRepository.findByName(FOOD_NAME)).hasSize(1);
    }

    /** 운영자가 O17로 옮긴 핀·바꾼 계좌를 재기동 시더가 되돌리면 안 된다 — 계좌 덮어쓰기는 감사 로그 우회다 */
    @Test
    @Order(3)
    void rerunNeverOverwritesExistingRows() {
        BoothEntity food = boothRepository.findByName(FOOD_NAME).getFirst();
        food.updateMapPosition(1, 2);
        food.updateCategory("ETC");
        food.updateBankAccount("운영자가 바꾼 계좌");
        boothRepository.save(food);
        String hashBefore = staffAccountRepository.findByLoginId(FOOD_LOGIN).orElseThrow().getPasswordHash();

        seeder.seed();

        BoothEntity after = boothRepository.findByName(FOOD_NAME).getFirst();
        assertThat(after.getMapX()).isEqualTo(1);
        assertThat(after.getMapY()).isEqualTo(2);
        assertThat(after.getCategory()).isEqualTo("ETC");
        assertThat(after.getBankAccount()).isEqualTo("운영자가 바꾼 계좌");
        assertThat(staffAccountRepository.findByLoginId(FOOD_LOGIN).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
    }

    /** 기존 부스가 있으면 그 부스에 빠진 계정만 붙인다 */
    @Test
    @Order(4)
    void attachesMissingAdminToExistingBooth() {
        staffAccountRepository.delete(staffAccountRepository.findByLoginId(CAFE_LOGIN).orElseThrow());

        EventSeeder.Result result = seeder.seed();

        assertThat(result).isEqualTo(new EventSeeder.Result(0, 2, 1, 1, false));
        StaffAccountEntity cafeAdmin = staffAccountRepository.findByLoginId(CAFE_LOGIN).orElseThrow();
        BoothEntity cafe = boothRepository.findByName(CAFE_NAME).getFirst();
        assertThat(jdbc.queryForObject("select booth_id from staff_account where login_id = ?", Long.class, CAFE_LOGIN))
                .isEqualTo(cafe.getId());
        assertThat(cafeAdmin.getRole()).isEqualTo(StaffRole.ADMIN);
    }

    /**
     * 두 번째 부스의 loginId가 이미 다른 부스 계정이면 실패하고, 같은 실행에서 먼저 만든 첫 부스까지 롤백된다.
     * "절반만 들어간 상태"가 남지 않는지 본다.
     */
    @Test
    @Order(5)
    void conflictFailsWholeRunAndRollsBack() {
        deleteSeededRows();
        BoothEntity other = boothRepository.save(new BoothEntity("시드 테스트 남의 부스", "계좌", null));
        staffAccountRepository.save(new StaffAccountEntity(other, CAFE_LOGIN,
                PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("whatever-1"),
                LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.ADMIN));
        long maps = eventMapRepository.count();

        assertThatThrownBy(() -> seeder.seed())
                .isInstanceOf(EventSeedException.class)
                .hasMessageContaining("행사 시딩 실패")
                .hasMessageContaining(CAFE_LOGIN);

        assertThat(boothRepository.findByName(FOOD_NAME)).isEmpty();
        assertThat(boothRepository.findByName(CAFE_NAME)).isEmpty();
        assertThat(staffAccountRepository.findByLoginId(FOOD_LOGIN)).isEmpty();
        assertThat(eventMapRepository.count()).isEqualTo(maps);
    }

    @Test
    @Order(6)
    void sameAccountWithDifferentRoleIsAConfigurationError() {
        deleteSeededRows();
        seeder.seed();
        // 같은 부스의 같은 loginId지만 STAFF — 시드는 ADMIN을 기대하므로 조용히 넘기지 않는다
        jdbc.update("update staff_account set role = 'STAFF' where login_id = ?", FOOD_LOGIN);

        assertThatThrownBy(() -> seeder.seed()).isInstanceOf(EventSeedException.class).hasMessageContaining(FOOD_LOGIN);
    }

    @Test
    @Order(7)
    void ambiguousBoothNameFails() {
        deleteSeededRows();
        boothRepository.save(new BoothEntity(FOOD_NAME, "계좌1", null));
        boothRepository.save(new BoothEntity(FOOD_NAME, "계좌2", null));

        assertThatThrownBy(() -> seeder.seed()).isInstanceOf(EventSeedException.class).hasMessageContaining("2건");
        assertThat(staffAccountRepository.findByLoginId(FOOD_LOGIN)).isEmpty();
    }

    /** 약도는 파일이 유일한 원본 — 최신 약도와 다를 때만 1건 추가 */
    @Test
    @Order(8)
    void mapIsAppendedOnlyWhenFileDiffersFromLatest() {
        deleteSeededRows();
        jdbc.update("insert into event_map (image_url, width, height, updated_at) values (?, ?, ?, ?)",
                "/uploads/event/old-seed-it.png", 800, 600, LocalDateTime.of(2026, 9, 1, 12, 0));
        long maps = eventMapRepository.count();

        assertThat(seeder.seed().mapCreated()).isTrue();
        assertThat(eventMapRepository.count()).isEqualTo(maps + 1);
        assertThat(eventMapRepository.findFirstByOrderByIdDesc().orElseThrow().getImageUrl()).isEqualTo(MAP_URL);

        assertThat(seeder.seed().mapCreated()).isFalse();
        assertThat(eventMapRepository.count()).isEqualTo(maps + 1);
    }

    /**
     * 시더가 만든 부스에서 "테이블 추가"(POST /admin/tables, next_table_seq 채번)와 O2 일괄 등록이 그대로 된다.
     * 시더는 테이블을 만들지 않으므로 채번은 1부터 시작하고, 시더를 다시 돌려도 카운터가 되돌아가지 않아야 한다.
     */
    @Test
    @Order(9)
    void seededBoothCanAddAndBulkCreateTables() throws Exception {
        String token = login(FOOD_LOGIN, FOOD_PASSWORD, FOOD_NAME);
        Long boothId = boothRepository.findByName(FOOD_NAME).getFirst().getId();

        mockMvc.perform(post("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("T-1"));
        mockMvc.perform(post("/api/v1/admin/tables/bulk").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[\"A1\",\"A2\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tables.length()").value(2));

        // 시더 재실행은 부스 행을 건드리지 않는다 — 채번 카운터도 그대로
        seeder.seed();
        assertThat(jdbc.queryForObject("select next_table_seq from booth where id = ?", Integer.class, boothId)).isEqualTo(2);

        mockMvc.perform(post("/api/v1/admin/tables").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("T-2"));
        assertThat(jdbc.queryForObject("select count(*) from booth_table where booth_id = ?", Long.class, boothId)).isEqualTo(4);
        // O16 tableCount도 시드 부스에서 활성 테이블 수를 그대로 센다
        mockMvc.perform(get("/api/v1/admin/booth").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.tableCount").value(4));
    }

    private String login(String loginId, String password, String boothName) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staff.role").value("ADMIN"))
                .andExpect(jsonPath("$.staff.boothName").value(boothName))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private void deleteSeededRows() {
        jdbc.update("delete from booth_table where booth_id in (select id from booth where name in (?, ?))", FOOD_NAME, CAFE_NAME);
        jdbc.update("delete from staff_account where login_id in (?, ?)", FOOD_LOGIN, CAFE_LOGIN);
        jdbc.update("delete from booth where name in (?, ?, ?)", FOOD_NAME, CAFE_NAME, "시드 테스트 남의 부스");
        jdbc.update("delete from event_map where image_url in (?, ?)", MAP_URL, "/uploads/event/old-seed-it.png");
    }
}
