package com.boothlock.boothlock_server.global.config;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.collection.AbstractCollectionPersister;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엔티티가 매핑하는 모든 테이블·컬럼이 {@code schema-mysql8.sql}에 있는지 본다.
 *
 * <p><b>왜 필요한가</b>: 운영(RDS)은 {@code ddl-auto=validate}라 엔티티에 컬럼을 추가하고 SQL 파일에
 * 안 넣으면 <b>서버가 아예 뜨지 않는다</b>({@code Schema validation: missing column}). 그런데 개발·테스트는
 * H2 {@code ddl-auto=update}라 컬럼이 자동으로 생겨서, 이 누락은 운영에 배포하는 순간에야 드러난다.
 *
 * <p>2026-09-23에 실제로 이런 PR이 열려 있었다 — 테이블에 grid_row·grid_col을 추가하면서 SQL 파일은
 * 그대로였다. RDS로 전환한 뒤 머지됐다면 배포 즉시 서버가 멈췄을 것이다.
 *
 * <p>대조 기준은 Hibernate가 실제로 쓰는 매핑(테스트 컨텍스트의 SessionFactory)이다 — 운영의 validate가
 * 보는 것과 같다. 필드 이름을 직접 파싱하지 않으므로 {@code @Column(name)}·기본 이름 규칙·조인 컬럼을
 * 전부 Hibernate가 해석한 그대로 비교한다.
 */
@SpringBootTest
class SchemaColumnParityTests {

    private static final Path SCHEMA = Path.of("docs/schema-mysql8.sql");
    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE\\s+`?(\\w+)`?\\s*\\((.*?)\\n\\)[^;]*;", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Set<String> NOT_A_COLUMN =
            Set.of("PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN", "CHECK");

    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void 엔티티가_쓰는_컬럼이_전부_스키마_파일에_있다() throws IOException {
        Map<String, Set<String>> sql = parseSchema();
        Map<String, Set<String>> mapped = mappedColumns();

        Set<String> missing = new TreeSet<>();
        mapped.forEach((table, columns) -> {
            Set<String> inSql = sql.get(table);
            if (inSql == null) {
                missing.add("테이블 " + table + " 전체");
                return;
            }
            for (String column : columns) {
                if (!inSql.contains(column)) missing.add(table + "." + column);
            }
        });

        assertTrue(missing.isEmpty(),
                "엔티티에는 있고 backend/docs/schema-mysql8.sql에는 없는 컬럼이 있습니다: " + missing
                        + " — 운영(RDS)은 ddl-auto=validate라 이대로 배포하면 서버가 뜨지 않습니다. "
                        + "SQL 파일의 CREATE TABLE에 컬럼을 추가하고, 이미 운영 중인 RDS에는 ALTER TABLE도 적용하세요.");
        assertTrue(mapped.size() >= 12, "대조한 테이블이 너무 적습니다(매핑을 못 읽었을 수 있음): " + mapped.keySet());
    }

    private Map<String, Set<String>> mappedColumns() {
        SessionFactoryImplementor sf = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        Map<String, Set<String>> result = new HashMap<>();
        sf.getMappingMetamodel().forEachEntityDescriptor(descriptor -> {
            if (!(descriptor instanceof AbstractEntityPersister p)) return;
            Set<String> cols = result.computeIfAbsent(normalize(p.getTableName()), k -> new HashSet<>());
            for (String id : p.getIdentifierColumnNames()) cols.add(normalize(id));
            for (int i = 0; i < p.getPropertyNames().length; i++) {
                String[] names = p.getPropertyColumnNames(i);
                if (names == null) continue;
                for (String name : names) if (name != null) cols.add(normalize(name));
            }
        });
        // 단방향 @OneToMany @JoinColumn(예: orders.items → order_item.order_id)은 컬렉션 쪽이 소유한다
        sf.getMappingMetamodel().forEachCollectionDescriptor(descriptor -> {
            if (!(descriptor instanceof AbstractCollectionPersister c)) return;
            Set<String> cols = result.computeIfAbsent(normalize(c.getTableName()), k -> new HashSet<>());
            for (String key : c.getKeyColumnNames()) cols.add(normalize(key));
        });
        return result;
    }

    private static Map<String, Set<String>> parseSchema() throws IOException {
        String text = Files.readString(SCHEMA, StandardCharsets.UTF_8);
        Map<String, Set<String>> tables = new HashMap<>();
        Matcher m = CREATE_TABLE.matcher(text);
        while (m.find()) {
            Set<String> cols = new HashSet<>();
            for (String line : m.group(2).split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
                String first = trimmed.split("\\s+")[0].replace("`", "");
                if (NOT_A_COLUMN.contains(first.toUpperCase(Locale.ROOT))) continue;
                cols.add(normalize(first));
            }
            tables.put(normalize(m.group(1)), cols);
        }
        return tables;
    }

    private static String normalize(String name) {
        return name.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }
}
