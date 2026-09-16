package com.boothlock.boothlock_server.event.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.event.domain.EventMapEntity;
import com.boothlock.boothlock_server.event.repository.EventMapRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 손님 홈 화면(E1·E2) 검증 — 대표 QR로 들어오는 공개 API.
 *
 * <p>데이터는 @Transactional 롤백으로 지운다. 부스·테이블을 미리 지우지 않고, 응답에서 <b>이 테스트가 만든 부스만</b>
 * 골라 검증한다 — 다른 테스트 클래스가 부스를 참조하는 행(계정·메뉴·주문)을 남겼을 때 부스 삭제가
 * 외래키 위반으로 깨지거나, 남은 부스 때문에 목록 순서가 밀려 엉뚱한 부스를 검증하는 일을 피하기 위해서다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
// 10초 캐시를 끈다 — 테스트마다 데이터를 바꾸고 바로 조회하므로 캐시가 켜져 있으면 앞 테스트 결과가 보인다.
// 캐시 동작 자체는 EventQueryServiceCacheTests에서 따로 검증한다
@TestPropertySource(properties = "boothlock.event.booths-cache-seconds=0")
class EventApiTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository boothRepository;
    @Autowired private TableRepository tableRepository;
    @Autowired private TableSessionRepository tableSessionRepository;
    @Autowired private EventMapRepository eventMapRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    private BoothEntity foodBooth;
    private BoothEntity cafeBooth;

    /**
     * 프로덕션 코드(EventQueryService·TableSessionService·TableSessionAuthService)가 전부
     * LocalDateTime.now(Asia/Seoul)로 시각을 만든다. 테스트 시딩도 같은 기준이어야 한다.
     *
     * <p>맨 LocalDateTime.now()를 쓰면 build.gradle의 `-Duser.timezone=Asia/Seoul` 고정에만 기대게 된다.
     * 고정을 UTC로 덮고 돌리면 idleSince(KST 현재 - 3시간)가 UTC 벽시계보다 6시간 미래가 되어
     * "5분 전 활동" 세션까지 유휴로 판정돼 좌석 집계 2건이 깨진다(실측). 서비스 쪽 시간대 회귀는
     * EventQueryServiceTimeZoneTests가 JVM 기본 시간대를 UTC로 바꿔 따로 잡는다.
     */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static LocalDateTime now() {
        return LocalDateTime.now(KST);
    }

    @BeforeEach
    void setUp() {
        // event_map은 다른 테이블이 참조하지 않아 지워도 외래키 문제가 없다. 롤백되므로 다른 테스트에도 영향이 없다
        eventMapRepository.deleteAll();

        foodBooth = boothRepository.save(new BoothEntity("컴공 주점", "카카오뱅크 3333-01-1234567 (홍길동)", "18:00~02:00"));
        foodBooth.updateCategory("FOOD");
        foodBooth.updateMapPosition(3200, 5400);
        cafeBooth = boothRepository.save(new BoothEntity("동아리 카페", "국민은행 9999-88-7777777 (김철수)", "12:00~20:00"));
        cafeBooth.updateCategory("CAFE");
        cafeBooth.updateMapPosition(6100, 2800);
        boothRepository.flush();
    }

    /** 응답에서 이 부스 한 건만 고르는 JSONPath */
    private static String booth(BoothEntity booth) {
        return "$.booths[?(@.boothId == " + booth.getId() + ")]";
    }

    /** 응답 중 이 테스트가 만든 부스들만의 id 목록 (순서 유지) */
    private List<Long> ownBoothIds(String body, BoothEntity... own) {
        List<Number> ids = JsonPath.read(body, "$.booths[*].boothId");
        List<Long> wanted = java.util.Arrays.stream(own).map(BoothEntity::getId).toList();
        return ids.stream().map(Number::longValue).filter(wanted::contains).toList();
    }

    /**
     * 빈자리 판정은 테이블 status가 아니라 세션으로 한다 — 지금은 status를 빈자리로 되돌리는 코드가 없어서다.
     * activityMinutesAgo가 null이면 세션 없음(빈자리), 값이 있으면 그만큼 전에 마지막 활동이 있었다는 뜻이다.
     */
    private TableEntity addTable(BoothEntity booth, String label, Integer activityMinutesAgo) {
        TableEntity table = tableRepository.save(new TableEntity(booth, label, "tok-" + booth.getId() + "-" + label));
        if (activityMinutesAgo != null) {
            table.occupy();
            tableSessionRepository.save(new TableSessionEntity(
                    table, "sess-" + booth.getId() + "-" + label, now().minusMinutes(activityMinutesAgo)));
            tableSessionRepository.flush();
        }
        tableRepository.flush();
        return table;
    }

    // ── E1 부스 목록·좌석 현황 ───────────────────────────

    @Test
    void returnsBoothsWithTableCounts() throws Exception {
        addTable(foodBooth, "A-1", 5);      // 5분 전 활동 — 이용 중
        addTable(foodBooth, "A-2", 5);
        addTable(foodBooth, "A-3", null);   // 세션 없음 — 빈자리
        addTable(cafeBooth, "B-1", null);

        String body = mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(booth(foodBooth) + ".name").value("컴공 주점"))
                .andExpect(jsonPath(booth(foodBooth) + ".category").value("FOOD"))
                .andExpect(jsonPath(booth(foodBooth) + ".isOpen").value(true))
                .andExpect(jsonPath(booth(foodBooth) + ".mapX").value(3200))
                .andExpect(jsonPath(booth(foodBooth) + ".mapY").value(5400))
                .andExpect(jsonPath(booth(foodBooth) + ".tables.total").value(3))
                .andExpect(jsonPath(booth(foodBooth) + ".tables.empty").value(1))     // 3개 중 2개 사용중
                .andExpect(jsonPath(booth(cafeBooth) + ".tables.total").value(1))
                .andExpect(jsonPath(booth(cafeBooth) + ".tables.empty").value(1))
                .andReturn().getResponse().getContentAsString();

        // boothId 오름차순 (명세 E1)
        assertThat(ownBoothIds(body, foodBooth, cafeBooth)).containsExactly(foodBooth.getId(), cafeBooth.getId());
    }

    @Test
    void neverExposesBankAccountOrSecrets() throws Exception {
        // 인증 없는 공개 API다 — 노출 목록 밖의 값이 한 글자라도 새면 안 된다 (§7-18)
        addTable(foodBooth, "A-1", 5);
        addTable(cafeBooth, "B-1", 5);

        String body = mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 키 이름뿐 아니라 값도 본다 — 필드명을 바꿔 새는 경우까지 잡기 위해서다
        assertThat(body)
                .doesNotContain("3333-01-1234567", "9999-88-7777777", "홍길동", "김철수")
                .doesNotContain("bankAccount", "operatingHours", "18:00~02:00", "12:00~20:00")
                .doesNotContain("tok-", "tableToken", "sess-", "sessionToken");
    }

    @Test
    void boothWithNoTablesReportsZero() throws Exception {
        // 테이블을 아직 등록하지 않은 부스는 집계 결과에 아예 없다 — 0으로 채워야 목록에서 빠지지 않는다
        mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(booth(foodBooth) + ".tables.total").value(0))
                .andExpect(jsonPath(booth(foodBooth) + ".tables.empty").value(0));
    }

    @Test
    void filtersByCategory() throws Exception {
        mockMvc.perform(get("/api/v1/event/booths").param("category", "CAFE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(booth(cafeBooth)).value(hasSize(1)))
                .andExpect(jsonPath(booth(foodBooth)).value(hasSize(0)));
    }

    @Test
    void categoryFilterIgnoresCase() throws Exception {
        mockMvc.perform(get("/api/v1/event/booths").param("category", "cafe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(booth(cafeBooth)).value(hasSize(1)))
                .andExpect(jsonPath(booth(foodBooth)).value(hasSize(0)));
    }

    @Test
    void blankCategoryMeansAll() throws Exception {
        for (String blank : new String[]{"", "  "}) {
            mockMvc.perform(get("/api/v1/event/booths").param("category", blank))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(booth(cafeBooth)).value(hasSize(1)))
                    .andExpect(jsonPath(booth(foodBooth)).value(hasSize(1)));
        }
    }

    @Test
    void categoryMustMatchWholeValue() throws Exception {
        mockMvc.perform(get("/api/v1/event/booths").param("category", "CA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(booth(cafeBooth)).value(hasSize(0)));
    }

    @Test
    void unknownCategoryReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/event/booths").param("category", "NOPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booths.length()").value(0));
    }

    @Test
    void halfCoordinateIsTreatedAsMissing() throws Exception {
        // 좌표는 둘 다 있어야 지도에 찍을 수 있다 — 한쪽만 있으면 없는 것으로 준다
        // 엔티티는 반쪽 좌표를 거부하므로 DB를 직접 고쳐 과거 데이터·수동 수정 상황을 만든다
        jdbcTemplate.update("update booth set map_y = null where id = ?", cafeBooth.getId());

        mockMvc.perform(get("/api/v1/event/booths").param("category", "CAFE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(booth(cafeBooth) + ".mapX").value(contains(nullValue())))
                .andExpect(jsonPath(booth(cafeBooth) + ".mapY").value(contains(nullValue())));
    }

    @Test
    void boothWithoutHomeInfoStoresNullAndIsListed() throws Exception {
        // 좌표·분류를 안 넣은 부스는 DB에 0이 아니라 NULL로 남아야 하고, 목록에는 null로 나온다
        BoothEntity bare = boothRepository.saveAndFlush(new BoothEntity("좌표 없는 부스", "은행 1", "10:00~20:00"));
        entityManager.clear();

        Map<String, Object> row = jdbcTemplate.queryForMap("select map_x, map_y, category from booth where id = ?", bare.getId());
        assertThat(row).containsEntry("MAP_X", null).containsEntry("MAP_Y", null).containsEntry("CATEGORY", null);

        mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(booth(bare)).value(hasSize(1)))
                .andExpect(jsonPath(booth(bare) + ".mapX").value(contains(nullValue())))
                .andExpect(jsonPath(booth(bare) + ".category").value(contains(nullValue())));
    }

    @Test
    void legacyBoothRowWithNullCoordinatesLoads() {
        // 컬럼이 생기기 전부터 있던 부스 행(좌표 NULL)을 엔티티로 읽을 수 있어야 한다 — 좌표가 int면 여기서 예외가 난다
        jdbcTemplate.update("insert into booth (name, bank_account, is_open) values ('기존 부스', '은행 2', true)");
        Long legacyId = jdbcTemplate.queryForObject("select id from booth where name = '기존 부스'", Long.class);
        entityManager.clear();

        BoothEntity legacy = boothRepository.findById(legacyId).orElseThrow();
        assertThat(legacy.getMapX()).isNull();
        assertThat(legacy.getMapY()).isNull();
        assertThat(legacy.getCategory()).isNull();
    }

    @Test
    void closedBoothStaysInList() throws Exception {
        // 주문을 마감한 부스도 목록에 남아야 손님이 헛걸음하지 않는다
        cafeBooth.updateOpen(false);
        boothRepository.flush();

        mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(booth(cafeBooth) + ".isOpen").value(false))
                .andExpect(jsonPath(booth(foodBooth) + ".isOpen").value(true));
    }

    @Test
    void idleTableCountsAsEmpty() throws Exception {
        // 지금은 status를 빈자리로 되돌리는 코드가 없다. status만 보면 손님이 떠나도 계속 사용중으로 남는다.
        // 마지막 활동이 임계(기본 3시간)를 지나면 빈자리로 센다
        addTable(foodBooth, "A-1", 5);        // 방금 활동 — 이용 중
        addTable(foodBooth, "A-2", 60 * 5);   // 5시간 전이 마지막 활동 — 손님이 떠난 것으로 본다

        mockMvc.perform(get("/api/v1/event/booths").param("category", "FOOD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(booth(foodBooth) + ".tables.total").value(2))
                .andExpect(jsonPath(booth(foodBooth) + ".tables.empty").value(1));
    }

    @Test
    void defaultIdleThresholdIsThreeHours() throws Exception {
        // 설정 기본값 180분 — 양쪽으로 10분 여유를 둬 실행 시간 차이에 흔들리지 않게 한다
        addTable(foodBooth, "A-1", 170);
        addTable(foodBooth, "A-2", 190);

        mockMvc.perform(get("/api/v1/event/booths").param("category", "FOOD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(booth(foodBooth) + ".tables.total").value(2))
                .andExpect(jsonPath(booth(foodBooth) + ".tables.empty").value(1));
    }

    @Test
    void checkedOutTableCountsAsEmptyImmediately() throws Exception {
        // 세션이 종료되면 유휴 시간을 기다리지 않고 바로 빈자리가 된다.
        // 세션을 종료하는 API(O6 퇴실)는 아직 스텁이라 여기서는 종료를 직접 기록해 앞으로의 계약을 고정한다
        TableEntity table = addTable(foodBooth, "A-1", 5);
        TableSessionEntity session = tableSessionRepository.findByTableIdAndEndedAtIsNull(table.getId()).orElseThrow();
        session.end(now());
        tableSessionRepository.flush();

        mockMvc.perform(get("/api/v1/event/booths").param("category", "FOOD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(booth(foodBooth) + ".tables.total").value(1))
                .andExpect(jsonPath(booth(foodBooth) + ".tables.empty").value(1));
    }

    @Test
    void duplicateActiveSessionsDoNotInflateTotal() throws Exception {
        // DB 유니크 제약이 활성 세션을 테이블당 1개로 막지만, 데이터가 오염돼도 테이블은 한 번만 센다
        TableEntity table = addTable(foodBooth, "A-1", 5);
        jdbcTemplate.update("update table_session set ended_at_key = id where table_id = ?", table.getId());
        tableSessionRepository.saveAndFlush(new TableSessionEntity(table, "sess-dup", now().minusMinutes(1)));

        mockMvc.perform(get("/api/v1/event/booths").param("category", "FOOD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(booth(foodBooth) + ".tables.total").value(1))
                .andExpect(jsonPath(booth(foodBooth) + ".tables.empty").value(0));
    }

    // ── E2 행사 약도 ────────────────────────────────────

    @Test
    void returnsEventMap() throws Exception {
        eventMapRepository.save(new EventMapEntity(
                "/uploads/event/map.png", 1600, 1200, LocalDateTime.of(2026, 9, 10, 14, 0)));

        mockMvc.perform(get("/api/v1/event/map"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("/uploads/event/map.png"))
                .andExpect(jsonPath("$.width").value(1600))
                .andExpect(jsonPath("$.height").value(1200))
                .andExpect(jsonPath("$.updatedAt").value("2026-09-10T14:00:00+09:00"));
    }

    @Test
    void returnsLatestMapById() throws Exception {
        // 약도를 교체하면 새 행이 쌓인다 — updatedAt은 사람이 넣는 값이라 id 기준으로 최신을 고른다
        eventMapRepository.save(new EventMapEntity("/uploads/event/old.png", 800, 600,
                LocalDateTime.of(2026, 12, 31, 23, 59)));
        eventMapRepository.save(new EventMapEntity("/uploads/event/new.png", 1600, 1200,
                LocalDateTime.of(2026, 1, 1, 0, 0)));

        mockMvc.perform(get("/api/v1/event/map"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("/uploads/event/new.png"));
    }

    @Test
    void missingMapIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/event/map"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ── API 문서 ────────────────────────────────────────

    @Test
    void swaggerKeepsEventBoothSchemaSeparateFromSessionBooth() throws Exception {
        // 명세상 개발 후에는 Swagger가 상세의 원본이다. 중첩 record 이름이 C1 응답의 Booth와 겹치면
        // E1 스키마가 name·isOpen 두 필드로 덮이는 것을 실측했다
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.EventBooth.properties.boothId").exists())
                .andExpect(jsonPath("$.components.schemas.EventBooth.properties.tables").exists())
                .andExpect(jsonPath("$.components.schemas.EventBoothTables.properties.empty").exists())
                .andExpect(jsonPath("$.components.schemas.Booth.properties.name").exists());
    }

    // ── 공개 축 ─────────────────────────────────────────

    @Test
    void worksWithoutAnyAuthHeader() throws Exception {
        // 대표 QR로 들어오는 화면이라 토큰이 없다. 지금은 전역 인증 필터가 없어 당연히 통과하지만,
        // 나중에 인증 필터가 생겼을 때 이 두 경로를 열어 두어야 한다는 계약을 고정한다
        mockMvc.perform(get("/api/v1/event/booths")).andExpect(status().isOk());
        eventMapRepository.save(new EventMapEntity("/uploads/event/map.png", 100, 100, now()));
        mockMvc.perform(get("/api/v1/event/map")).andExpect(status().isOk());
    }
}
