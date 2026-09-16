package com.boothlock.boothlock_server.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시딩 입력 검증 — DB 없이 파일 형식만 본다.
 * 여기서 걸러지지 않은 값은 그대로 행사 DB에 들어가므로, 틀린 파일은 전부 기동 실패여야 한다.
 */
class EventSeedParserTests {

    private static final Map<String, String> ENV = Map.of(
            "SEED_PW_1", "first-booth-password",
            "SEED_PW_2", "second-booth-password",
            "SEED_PW_SHORT", "short",
            "SEED_PW_PLACEHOLDER", "CHANGE_ME-please");

    private final EventSeedParser parser = new EventSeedParser(ENV::get);

    private static String booth(String name, String category, String mapX, String mapY, String admin) {
        return """
                {"name": "%s", "bankAccount": "카카오뱅크 3333-01-1234567 (홍길동)", "category": %s,
                 "mapX": %s, "mapY": %s, "admin": %s}""".formatted(name, category, mapX, mapY, admin);
    }

    private static String admin(String loginId, String envName) {
        return "{\"loginId\": \"%s\", \"passwordEnv\": \"%s\"}".formatted(loginId, envName);
    }

    private static final String MAP = "{\"imageUrl\": \"/uploads/event/map.png\", \"width\": 1600, \"height\": 1200}";

    private static String file(String... booths) {
        return "{\"booths\": [" + String.join(",", booths) + "], \"eventMap\": " + MAP + "}";
    }

    private static String validBooth() {
        return booth("컴공 주점", "\"FOOD\"", "3200", "5400", admin("k7q-food", "SEED_PW_1"));
    }

    private EventSeedPlan parse(String json) {
        return parser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private void assertRejected(String json, String messagePart) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(EventSeedException.class).hasMessageContaining(messagePart);
    }

    @Test
    @DisplayName("정상 파일은 모든 값을 그대로 담는다")
    void parsesValidFile() {
        String second = """
                {"name": "동아리 카페", "bankAccount": "국민 9999", "category": "CAFE", "mapX": 0, "mapY": 10000,
                 "operatingHours": "12:00~20:00", "admin": {"loginId": "z9-cafe", "password": "literal-password-1"}}""";
        EventSeedPlan plan = parse(file(validBooth(), second));

        assertThat(plan.booths()).hasSize(2);
        EventSeedPlan.BoothSeed first = plan.booths().get(0);
        assertThat(first.name()).isEqualTo("컴공 주점");
        assertThat(first.category()).isEqualTo("FOOD");
        assertThat(first.mapX()).isEqualTo(3200);
        assertThat(first.mapY()).isEqualTo(5400);
        assertThat(first.operatingHours()).isNull();
        assertThat(first.admin().loginId()).isEqualTo("k7q-food");
        assertThat(first.admin().password()).isEqualTo("first-booth-password");
        EventSeedPlan.BoothSeed cafe = plan.booths().get(1);
        assertThat(cafe.mapX()).isZero();
        assertThat(cafe.mapY()).isEqualTo(10000);
        assertThat(cafe.operatingHours()).isEqualTo("12:00~20:00");
        assertThat(cafe.admin().password()).isEqualTo("literal-password-1");
        assertThat(plan.eventMap()).isEqualTo(new EventSeedPlan.MapSeed("/uploads/event/map.png", 1600, 1200));
    }

    @Test
    @DisplayName("비밀번호는 toString에 나오지 않는다")
    void adminToStringHidesPassword() {
        EventSeedPlan plan = parse(file(validBooth()));
        assertThat(plan.toString()).doesNotContain("first-booth-password").contains("k7q-food");
    }

    // ── 좌표 ─────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"-1", "10001", "3200.5", "3200.0", "1e3", "\"3200\"", "null", "true", "99999999999"})
    @DisplayName("mapX가 0~10000 정수가 아니면 거부한다")
    void rejectsBadMapX(String mapX) {
        assertRejected(file(booth("컴공 주점", "\"FOOD\"", mapX, "5400", admin("k7q-food", "SEED_PW_1"))),
                "booths[0].mapX");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "10001", "0.5"})
    @DisplayName("mapY도 같은 규칙")
    void rejectsBadMapY(String mapY) {
        assertRejected(file(booth("컴공 주점", "\"FOOD\"", "0", mapY, admin("k7q-food", "SEED_PW_1"))),
                "booths[0].mapY");
    }

    @Test
    @DisplayName("좌표가 하나라도 없으면 거부한다 — 반쪽 좌표 부스는 지도에서 빠진다")
    void rejectsMissingCoordinate() {
        String noMapY = """
                {"name": "컴공 주점", "bankAccount": "계좌", "category": "FOOD", "mapX": 3200,
                 "admin": {"loginId": "k7q-food", "passwordEnv": "SEED_PW_1"}}""";
        assertRejected(file(noMapY), "booths[0].mapY");
    }

    @Test
    @DisplayName("오류 위치를 두 번째 부스까지 정확히 짚는다")
    void pointsAtOffendingBooth() {
        assertRejected(file(validBooth(),
                booth("동아리 카페", "\"CAFE\"", "10001", "0", admin("z9-cafe", "SEED_PW_2"))), "booths[1].mapX");
    }

    // ── category ─────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"\"food\"", "\" FOOD\"", "\"DRINK\"", "\"\"", "null", "1"})
    @DisplayName("category는 대문자 4개 값만")
    void rejectsBadCategory(String category) {
        assertRejected(file(booth("컴공 주점", category, "0", "0", admin("k7q-food", "SEED_PW_1"))),
                "booths[0].category");
    }

    // ── 필수·형식 ────────────────────────────────────

    @Test
    void rejectsUnknownFieldSoTyposAreNotIgnored() {
        String typo = """
                {"name": "컴공 주점", "bankAccount": "계좌", "category": "FOOD", "mapX": 1, "mapY": 2, "mapx": 3,
                 "admin": {"loginId": "k7q-food", "passwordEnv": "SEED_PW_1"}}""";
        assertRejected(file(typo), "booths[0].mapx");
    }

    @Test
    void rejectsDuplicateJsonKey() {
        String dup = """
                {"name": "컴공 주점", "bankAccount": "계좌", "category": "FOOD", "mapX": 1, "mapY": 2, "mapX": 3,
                 "admin": {"loginId": "k7q-food", "passwordEnv": "SEED_PW_1"}}""";
        assertRejected(file(dup), "JSON");
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertRejected("{\"eventMap\": " + MAP + "}", "booths");
        assertRejected("{\"booths\": [], \"eventMap\": " + MAP + "}", "booths");
        assertRejected("{\"booths\": [" + validBooth() + "]}", "eventMap");
        assertRejected(file("""
                {"name": "컴공 주점", "category": "FOOD", "mapX": 1, "mapY": 2,
                 "admin": {"loginId": "k7q-food", "passwordEnv": "SEED_PW_1"}}"""), "booths[0].bankAccount");
        assertRejected(file("""
                {"name": "컴공 주점", "bankAccount": "계좌", "category": "FOOD", "mapX": 1, "mapY": 2}"""),
                "booths[0].admin");
        assertRejected(file(booth("", "\"FOOD\"", "1", "2", admin("k7q-food", "SEED_PW_1"))), "booths[0].name");
        assertRejected(file(booth("컴공 주점 ", "\"FOOD\"", "1", "2", admin("k7q-food", "SEED_PW_1"))), "공백");
        assertRejected(file(booth("a".repeat(51), "\"FOOD\"", "1", "2", admin("k7q-food", "SEED_PW_1"))), "50자");
    }

    @Test
    void rejectsDuplicatesInsideFile() {
        assertRejected(file(validBooth(), booth("컴공 주점", "\"CAFE\"", "1", "2", admin("other", "SEED_PW_2"))),
                "booths[1].name");
        // MySQL 기본 콜레이션은 대소문자 무시 — 파일 검사도 같은 기준
        assertRejected(file(validBooth(), booth("카페", "\"CAFE\"", "1", "2", admin("K7Q-FOOD", "SEED_PW_2"))),
                "booths[1].admin.loginId");
    }

    @Test
    void rejectsBadEventMap() {
        String base = "{\"booths\": [" + validBooth() + "], \"eventMap\": ";
        for (String url : new String[]{"javascript:alert(1)", "//evil.example/a.png", "https://cdn.example/map.png",
                "/uploads/menu/map.png", "uploads/event/map.png", "/uploads/event/", "/uploads/event/../secret.png",
                "/uploads/event/a/../../x.png", "/uploads/event/./map.png", "/uploads/event/..",
                "/uploads/event/%2e%2e/x.png", "/uploads/event/a\\\\..\\\\x.png", "/uploads/event/map.png?v=1",
                "/uploads/event//map.png", "/uploads/event/약도.png", "/uploads/event/my map.png"}) {
            assertRejected(base + "{\"imageUrl\": \"" + url + "\", \"width\": 1, \"height\": 1}}", "imageUrl");
        }
        assertThat(parse(base + "{\"imageUrl\": \"/uploads/event/2026/map_v1-final.png\", \"width\": 1, \"height\": 1}}")
                .eventMap().imageUrl()).isEqualTo("/uploads/event/2026/map_v1-final.png");
        assertRejected(base + "{\"imageUrl\": \"/uploads/event/map.png\", \"width\": 0, \"height\": 1}}", "width");
        assertRejected(base + "{\"imageUrl\": \"/uploads/event/map.png\", \"width\": 10, \"height\": 1.5}}", "height");
        assertRejected(base + "{\"imageUrl\": \"/uploads/event/map.png\", \"width\": 10}}", "height");
    }

    // ── 비밀번호 ─────────────────────────────────────

    @Test
    void requiresExactlyOnePasswordSource() {
        assertRejected(file(booth("컴공 주점", "\"FOOD\"", "1", "2",
                "{\"loginId\": \"k7q-food\", \"password\": \"literal-password-1\", \"passwordEnv\": \"SEED_PW_1\"}")),
                "정확히 하나");
        assertRejected(file(booth("컴공 주점", "\"FOOD\"", "1", "2", "{\"loginId\": \"k7q-food\"}")), "정확히 하나");
    }

    @Test
    void rejectsMissingEnvironmentVariableWithoutLeakingAnything() {
        assertRejected(file(booth("컴공 주점", "\"FOOD\"", "1", "2", admin("k7q-food", "NOT_SET_ANYWHERE"))),
                "NOT_SET_ANYWHERE");
    }

    @Test
    void rejectsWeakOrPlaceholderPasswords() {
        assertRejected(file(booth("컴공 주점", "\"FOOD\"", "1", "2", admin("k7q-food", "SEED_PW_SHORT"))), "8자");
        assertRejected(file(booth("컴공 주점", "\"FOOD\"", "1", "2", admin("k7q-food", "SEED_PW_PLACEHOLDER"))),
                "자리표시자");
        // bcrypt 72바이트 한계 — 한글 25자 = 75바이트
        assertRejected(file(booth("컴공 주점", "\"FOOD\"", "1", "2",
                "{\"loginId\": \"k7q-food\", \"password\": \"" + "가".repeat(25) + "\"}")), "72바이트");
    }

    @Test
    @DisplayName("깨진 JSON의 오류 메시지에 파일 내용(비밀번호일 수 있음)을 인용하지 않는다")
    void malformedJsonMessageDoesNotQuoteContent() {
        String broken = "{\"booths\": [{\"admin\": {\"loginId\": \"x\", \"password\": hunter2secret}}]}";
        assertThatThrownBy(() -> parse(broken))
                .isInstanceOf(EventSeedException.class)
                .hasMessageContaining("JSON")
                .hasMessageNotContaining("hunter2secret");
    }

    // ── 저장소에 있는 예시 파일 ─────────────────────────

    private static String exampleFile() throws Exception {
        return Files.readString(Path.of("src/main/resources/seed/event-seed.example.json"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("예시 파일은 그대로는 시딩되지 않는다 — 자리표시자가 남아 있으면 기동 실패")
    void exampleFileIsRejectedAsIs() throws Exception {
        assertRejected(exampleFile(), "자리표시자");
    }

    @Test
    @DisplayName("예시 파일은 자리표시자만 바꾸면 유효한 형식이다 — 문서가 코드와 어긋나지 않았는지")
    void exampleFileIsValidOnceFilledIn() throws Exception {
        String filled = exampleFile().replace("CHANGE_ME", "X");
        EventSeedPlan plan = new EventSeedParser(name -> "a-real-password-" + name).parse(filled.getBytes(StandardCharsets.UTF_8));
        assertThat(plan.booths()).hasSize(2);
        assertThat(exampleFile()).doesNotContainPattern("\"password\"\\s*:");
    }
}
