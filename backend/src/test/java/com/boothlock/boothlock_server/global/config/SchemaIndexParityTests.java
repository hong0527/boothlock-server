package com.boothlock.boothlock_server.global.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엔티티의 {@code @Index}와 {@code schema-mysql8.sql}이 어긋나지 않게 한다.
 *
 * <p><b>왜 필요한가</b>: 운영은 {@code ddl-auto=validate}인데 validate는 <b>인덱스를 검사하지 않는다</b>.
 * 테이블·컬럼·타입만 본다. 그래서 엔티티에 인덱스를 추가하고 SQL 파일에 안 넣으면
 * 서버는 멀쩡히 뜨는데 인덱스만 조용히 없다. 느려질 뿐 에러가 안 나서 알아채기 어렵다.
 *
 * <p>실제로 #102가 {@code idx_orders_settlement}를 엔티티에만 넣어서, RDS에 그 인덱스가
 * 없는 채로 정산 CSV가 부스 주문을 전부 훑을 뻔했다. 주문이 수천 건 쌓이는 축제 마지막 날에
 * 드러났을 문제다.
 *
 * <p>H2(개발·테스트)는 {@code ddl-auto=update}라 인덱스가 자동으로 생기고, 그래서 로컬에서는
 * 영원히 안 걸린다. 이 테스트가 그 간극을 메운다.
 */
class SchemaIndexParityTests {

    private static final Path SCHEMA = Path.of("docs/schema-mysql8.sql");
    private static final Path ENTITY_ROOT = Path.of("src/main/java/com/boothlock/boothlock_server");

    /** {@code @Index(name = "idx_x", columnList = "...")} 에서 이름만 뽑는다 */
    private static final Pattern ENTITY_INDEX = Pattern.compile("@Index\\s*\\(\\s*name\\s*=\\s*\"(\\w+)\"");

    @Test
    void 엔티티에_선언한_인덱스가_스키마_파일에도_있다() throws IOException {
        String schema = Files.readString(SCHEMA, StandardCharsets.UTF_8);
        Set<String> missing = new LinkedHashSet<>();

        try (Stream<Path> files = Files.walk(ENTITY_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith("Entity.java")).toList()) {
                Matcher m = ENTITY_INDEX.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    String name = m.group(1);
                    // 스키마에 KEY/INDEX 선언으로 들어 있는지 — 주석 안의 언급은 세지 않는다
                    if (!schema.matches("(?s).*\\b(KEY|INDEX)\\s+" + Pattern.quote(name) + "\\s*\\(.*")) {
                        missing.add(name + "  (" + file.getFileName() + ")");
                    }
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "엔티티에만 있고 schema-mysql8.sql에 없는 인덱스가 있습니다. "
                        + "운영은 ddl-auto=validate라 인덱스를 검사하지 않으므로, 서버는 뜨지만 인덱스가 조용히 없습니다. "
                        + "SQL 파일에 KEY를 추가하고 RDS에도 CREATE INDEX 하세요: " + missing);
    }
}
