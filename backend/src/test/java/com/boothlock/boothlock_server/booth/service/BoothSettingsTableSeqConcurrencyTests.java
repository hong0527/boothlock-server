package com.boothlock.boothlock_server.booth.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.tableqr.service.TableAdminService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * O17 부스 설정 변경 vs "테이블 추가"(next_table_seq 채번) 경쟁 — audit2-code H3.
 *
 * <p>O17은 부스 행을 잠그지 않고 읽어 고친다. 엔티티가 전체 컬럼 UPDATE를 하면 그 사이 다른 트랜잭션이 올린
 * next_table_seq를 읽은 옛 값으로 되돌리고, 이후 "테이블 추가"가 같은 번호를 다시 내 라벨 중복(500)이 영구히 난다.
 * {@code @DynamicUpdate}로 바뀐 컬럼만 쓰면 되돌아가지 않는다.
 *
 * <p>이 클래스는 @Transactional이 아니다 — 두 트랜잭션이 실제로 따로 커밋돼야 하는 시험이다.
 */
@SpringBootTest
class BoothSettingsTableSeqConcurrencyTests {

    @Autowired BoothSettingsService settingsService;
    @Autowired TableAdminService tableAdminService;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EntityManager entityManager;
    /** O17이 부스를 "읽은 직후" 끼어들 지점을 만들기 위해 감시한다 — 나머지 호출은 전부 실제 구현으로 간다 */
    @MockitoSpyBean BoothRepository boothRepository;

    private Long boothId;
    private String auth;

    @BeforeEach
    void setUp() {
        BoothEntity booth = boothRepository.save(new BoothEntity("경쟁 시험 부스", "계좌", "10:00~20:00"));
        boothId = booth.getId();
        StaffAccountEntity admin = staffRepository.save(new StaffAccountEntity(booth, "race-admin",
                PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password"),
                LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.ADMIN));
        auth = "Bearer " + jwtProvider.issue(admin, Instant.now());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from booth_table where booth_id = ?", boothId);
        jdbc.update("delete from staff_account where booth_id = ?", boothId);
        jdbc.update("delete from booth where id = ?", boothId);
    }

    /**
     * 가장 불리한 순서를 고정한다: O17이 부스(next_table_seq=1)를 읽음 → 다른 트랜잭션이 테이블을 추가·커밋(=2)
     * → O17 커밋. 전체 컬럼 UPDATE였다면 여기서 1로 되돌아가고 다음 "테이블 추가"가 T-1을 다시 내 실패한다.
     */
    @Test
    void settingsCommitAfterConcurrentTableAddKeepsCounter() throws Exception {
        AtomicBoolean armed = new AtomicBoolean(true);
        AtomicReference<Throwable> addFailure = new AtomicReference<>();
        doAnswer(invocation -> {
            // 인터페이스 스파이는 callRealMethod가 안 된다 — SimpleJpaRepository.findById와 같은 em.find를 현재(O17) 트랜잭션에서 직접 한다
            Optional<BoothEntity> loaded = Optional.ofNullable(entityManager.find(BoothEntity.class, invocation.getArgument(0)));
            // 첫 findById(O17의 부스 조회) 한 번만 끼어든다 — 이후 호출·다른 스레드 호출은 그대로 통과
            if (armed.compareAndSet(true, false)) {
                Thread adder = new Thread(() -> {
                    try { tableAdminService.addSingleTable(auth); } catch (Throwable t) { addFailure.set(t); }
                });
                adder.start();
                adder.join();
            }
            return loaded;
        }).when(boothRepository).findById(any());

        var response = settingsService.update(auth, objectMapper.readTree("{\"category\":\"FOOD\"}"));

        assertThat(addFailure.get()).isNull();
        assertThat(response.category()).isEqualTo("FOOD");
        assertThat(nextTableSeq()).as("O17 커밋이 채번 카운터를 되돌리면 안 된다").isEqualTo(2);
        assertThat(tableAdminService.addSingleTable(auth).label()).isEqualTo("T-2");
        assertThat(jdbc.queryForObject("select category from booth where id = ?", String.class, boothId)).isEqualTo("FOOD");
    }

    /** 순서를 고정하지 않은 실제 동시 호출 — 100판 모두 채번이 이어지고 되돌아감이 0건이어야 한다 */
    @Test
    void parallelSettingsAndTableAddsNeverRevertCounter() throws Exception {
        int rounds = 100;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                String hours = "{\"operatingHours\":\"라운드 " + i + "\"}";
                CountDownLatch start = new CountDownLatch(1);
                Future<?> settings = pool.submit(() -> {
                    start.await();
                    settingsService.update(auth, objectMapper.readTree(hours));
                    return null;
                });
                Future<?> add = pool.submit(() -> {
                    start.await();
                    return tableAdminService.addSingleTable(auth);
                });
                start.countDown();
                settings.get();
                add.get();
                assertThat(nextTableSeq()).as("%d판째 뒤 next_table_seq", i + 1).isEqualTo(i + 2);
            }
        } finally {
            pool.shutdownNow();
        }

        List<String> labels = jdbc.queryForList(
                "select label from booth_table where booth_id = ? order by id", String.class, boothId);
        assertThat(labels).containsExactlyElementsOf(IntStream.rangeClosed(1, rounds).mapToObj(n -> "T-" + n).toList());
        assertThat(jdbc.queryForObject("select operating_hours from booth where id = ?", String.class, boothId))
                .isEqualTo("라운드 " + (rounds - 1));
    }

    private int nextTableSeq() {
        return jdbc.queryForObject("select next_table_seq from booth where id = ?", Integer.class, boothId);
    }
}
