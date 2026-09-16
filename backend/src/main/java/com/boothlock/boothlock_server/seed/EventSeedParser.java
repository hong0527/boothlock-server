package com.boothlock.boothlock_server.seed;

import com.boothlock.boothlock_server.booth.domain.BoothCategory;
import com.boothlock.boothlock_server.booth.service.BoothSettingsService;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 시딩 JSON을 읽어 검증한다 — DB를 건드리지 않는 순수 단계.
 *
 * <p>행사 당일 최악은 "조용히 절반만 들어가는 것"이다. 그래서 파일 전체를 먼저 검사하고
 * 하나라도 틀리면 어느 항목이 왜 틀렸는지(예: {@code booths[2].mapX}) 담아 던진다.
 * Jackson 3은 모르는 필드를 기본으로 무시하므로 트리로 읽어 직접 검사한다 —
 * {@code "mapx"} 같은 오타가 조용히 무시되면 그 부스는 지도에서 빠진다.
 *
 * <p>비밀번호는 {@code password}(저장소 밖 파일에 둘 때) 또는 {@code passwordEnv}(환경 변수 이름) 중
 * 정확히 하나로 받는다. 예시 파일의 자리표시자({@value #PLACEHOLDER})가 남아 있으면 거부한다.
 */
final class EventSeedParser {

    static final String PLACEHOLDER = "CHANGE_ME";
    static final int MIN_PASSWORD_LENGTH = 8;
    /** bcrypt는 72바이트 뒤를 버린다 — Spring은 초과 시 예외를 던지므로 기동 전에 이유를 알려준다 */
    static final int MAX_PASSWORD_BYTES = 72;

    private static final Set<String> ROOT_FIELDS = Set.of("booths", "eventMap");
    private static final Set<String> BOOTH_FIELDS =
            Set.of("name", "bankAccount", "category", "mapX", "mapY", "operatingHours", "admin");
    private static final Set<String> ADMIN_FIELDS = Set.of("loginId", "password", "passwordEnv");
    private static final Set<String> MAP_FIELDS = Set.of("imageUrl", "width", "height");
    /** EventUploadWebConfig의 서빙 경로 — 약도 파일은 boothlock.upload.event-dir 아래에 있어야 한다 */
    static final String MAP_URL_PREFIX = "/uploads/event/";
    private static final Pattern SAFE_MAP_PATH = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    private static final JsonMapper MAPPER = JsonMapper.builder()
            // 같은 키가 두 번 나오면 뒤엣것이 조용히 이긴다 — 복사·붙여넣기 실수를 잡는다
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Function<String, String> environment;

    /** @param environment 환경 변수 이름 → 값 (없으면 null). 운영에선 Spring Environment를 넘긴다 */
    EventSeedParser(Function<String, String> environment) {
        this.environment = environment;
    }

    EventSeedPlan parse(byte[] json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(new String(json, StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            // 원문 메시지는 깨진 토큰(따옴표 빠진 비밀번호 등)을 그대로 인용할 수 있어 위치만 싣는다
            String where = e.getLocation() == null ? ""
                    : " (줄 " + e.getLocation().getLineNr() + ", 열 " + e.getLocation().getColumnNr() + ")";
            throw new EventSeedException("시딩 파일이 올바른 JSON이 아닙니다" + where);
        }
        if (root == null || !root.isObject()) throw new EventSeedException("시딩 파일 최상위는 JSON 객체여야 합니다.");
        checkFields(root, ROOT_FIELDS, "");

        JsonNode boothsNode = root.get("booths");
        if (boothsNode == null || !boothsNode.isArray() || boothsNode.isEmpty())
            throw new EventSeedException("booths는 1개 이상인 배열이어야 합니다.");

        List<EventSeedPlan.BoothSeed> booths = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> loginIds = new HashSet<>();
        for (int i = 0; i < boothsNode.size(); i++) {
            EventSeedPlan.BoothSeed booth = booth(boothsNode.get(i), "booths[" + i + "]");
            // MySQL 기본 콜레이션은 대소문자를 구분하지 않는다 — 파일 안 중복도 같은 기준으로 본다
            if (!names.add(booth.name().toLowerCase(Locale.ROOT)))
                throw new EventSeedException("booths[" + i + "].name이 파일 안에서 중복됩니다: " + booth.name());
            if (!loginIds.add(booth.admin().loginId().toLowerCase(Locale.ROOT)))
                throw new EventSeedException("booths[" + i + "].admin.loginId가 파일 안에서 중복됩니다: "
                        + booth.admin().loginId());
            booths.add(booth);
        }

        JsonNode mapNode = root.get("eventMap");
        if (mapNode == null || !mapNode.isObject())
            throw new EventSeedException("eventMap(약도 1건)이 필요합니다 — 없으면 손님 홈 화면에 지도가 뜨지 않습니다.");
        return new EventSeedPlan(List.copyOf(booths), eventMap(mapNode));
    }

    private EventSeedPlan.BoothSeed booth(JsonNode node, String path) {
        if (node == null || !node.isObject()) throw new EventSeedException(path + "는 객체여야 합니다.");
        checkFields(node, BOOTH_FIELDS, path + ".");

        String name = trimmedText(node, "name", 50, path);
        String bankAccount = text(node, "bankAccount", 100, path);
        String category = text(node, "category", 20, path);
        if (!BoothCategory.isValid(category))
            throw new EventSeedException(path + ".category는 FOOD, CAFE, GOODS, ETC 중 하나여야 합니다 (대문자): " + category);
        int mapX = coordinate(node, "mapX", path);
        int mapY = coordinate(node, "mapY", path);
        String operatingHours = null;
        JsonNode hours = node.get("operatingHours");
        if (hours != null && !hours.isNull()) operatingHours = text(node, "operatingHours", 50, path);

        JsonNode adminNode = node.get("admin");
        if (adminNode == null || !adminNode.isObject())
            throw new EventSeedException(path + ".admin(부스 ADMIN 계정)이 필요합니다.");
        return new EventSeedPlan.BoothSeed(name, bankAccount, category, mapX, mapY, operatingHours,
                admin(adminNode, path + ".admin"));
    }

    private EventSeedPlan.AdminSeed admin(JsonNode node, String path) {
        checkFields(node, ADMIN_FIELDS, path + ".");
        String loginId = trimmedText(node, "loginId", 50, path);

        boolean literal = node.has("password");
        boolean fromEnv = node.has("passwordEnv");
        if (literal == fromEnv)
            throw new EventSeedException(path + "에는 password와 passwordEnv 중 정확히 하나만 있어야 합니다.");
        String password;
        if (literal) {
            JsonNode value = node.get("password");
            if (!value.isString()) throw new EventSeedException(path + ".password는 문자열이어야 합니다.");
            password = value.stringValue();
        } else {
            String envName = text(node, "passwordEnv", 200, path);
            if (!ENV_NAME.matcher(envName).matches())
                throw new EventSeedException(path + ".passwordEnv가 환경 변수 이름 형식이 아닙니다: " + envName);
            password = environment.apply(envName);
            // 값은 절대 메시지에 싣지 않는다 — 이름만
            if (password == null || password.isEmpty())
                throw new EventSeedException(path + ".passwordEnv로 지정한 환경 변수 " + envName + "가 비어 있거나 없습니다.");
        }
        if (password.contains(PLACEHOLDER))
            throw new EventSeedException(path + "의 비밀번호가 예시 자리표시자 그대로입니다.");
        if (password.isBlank() || password.length() < MIN_PASSWORD_LENGTH)
            throw new EventSeedException(path + "의 비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다.");
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES)
            throw new EventSeedException(path + "의 비밀번호는 UTF-8 " + MAX_PASSWORD_BYTES + "바이트 이하여야 합니다.");
        return new EventSeedPlan.AdminSeed(loginId, password);
    }

    private EventSeedPlan.MapSeed eventMap(JsonNode node) {
        checkFields(node, MAP_FIELDS, "eventMap.");
        String imageUrl = text(node, "imageUrl", 300, "eventMap");
        // 서버가 실제로 서빙하는 곳(/uploads/event/**)만 받는다 — 외부 주소나 다른 경로는 시더가 파일 존재를 확인할 수 없어
        // E2는 200인데 이미지만 404로 깨지는 상태를 기동 시점에 잡지 못한다.
        // 파일명은 영문·숫자·._- 만, ".." 구간은 거부 — 약도 폴더 밖 파일을 가리키는 경로를 원천 차단한다
        if (!imageUrl.startsWith(MAP_URL_PREFIX) || !SAFE_MAP_PATH.matcher(imageUrl.substring(MAP_URL_PREFIX.length())).matches())
            throw new EventSeedException("eventMap.imageUrl은 " + MAP_URL_PREFIX + " 아래 파일 경로여야 합니다"
                    + " (영문·숫자·._- 만, 예: " + MAP_URL_PREFIX + "map.png): " + imageUrl);
        for (String segment : imageUrl.substring(MAP_URL_PREFIX.length()).split("/")) {
            if (segment.equals("..") || segment.equals("."))
                throw new EventSeedException("eventMap.imageUrl에 . 또는 .. 경로 구간을 쓸 수 없습니다: " + imageUrl);
        }
        return new EventSeedPlan.MapSeed(imageUrl, positiveInt(node, "width"), positiveInt(node, "height"));
    }

    private static void checkFields(JsonNode node, Set<String> allowed, String prefix) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name))
                throw new EventSeedException("지원하지 않는 필드입니다: " + prefix + name + " (허용: " + allowed + ")");
        }
    }

    private static String text(JsonNode node, String field, int max, String path) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank())
            throw new EventSeedException(path + "." + field + "는 비어 있지 않은 문자열이어야 합니다.");
        String text = value.stringValue();
        if (text.length() > max) throw new EventSeedException(path + "." + field + "는 " + max + "자 이하여야 합니다.");
        if (text.contains(PLACEHOLDER))
            throw new EventSeedException(path + "." + field + "가 예시 자리표시자 그대로입니다: " + text);
        return text;
    }

    /** 멱등 판단(같은 이름·같은 loginId)에 쓰이는 값 — 앞뒤 공백이 섞이면 "다른 값"으로 보여 중복 생성된다 */
    private static String trimmedText(JsonNode node, String field, int max, String path) {
        String text = text(node, field, max, path);
        if (!text.equals(text.strip()))
            throw new EventSeedException(path + "." + field + "의 앞뒤에 공백이 있습니다: \"" + text + "\"");
        return text;
    }

    private static int coordinate(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        int max = BoothSettingsService.MAP_COORDINATE_MAX;
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 0 || value.intValue() > max)
            throw new EventSeedException(path + "." + field + "는 0~" + max + " 정수여야 합니다: " + value);
        return value.intValue();
    }

    private static int positiveInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0)
            throw new EventSeedException("eventMap." + field + "는 1 이상의 정수(px)여야 합니다: " + value);
        return value.intValue();
    }
}
