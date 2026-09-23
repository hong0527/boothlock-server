package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * O22b — 두 운영자가 서로 다른 테이블을 같은 칸에 동시에 놓으면 한쪽만 성공해야 한다.
 *
 * <p>잠금 없는 중복 확인이었을 때는 두 요청이 모두 "칸이 비었다"를 보고 둘 다 200을 받았다.
 * 그러면 그리드에서 두 카드가 같은 칸에 겹쳐 하나가 가려진다. 축제 전 35개를 여러 태블릿으로
 * 나눠 배치하면 실제로 생길 수 있는 경합이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TableGridPositionConcurrencyTests {

    private static final int ROUNDS = 15;
    private static final int RACERS = 4;

    @Autowired MockMvc mockMvc;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;

    private String authorization;
    private final List<TableEntity> tables = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cleanUp();
        BoothEntity booth = boothRepository.save(new BoothEntity("경합 부스", "은행 1234", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        StaffAccountEntity staff = staffRepository.save(new StaffAccountEntity(booth, "race-admin", hash,
                LocalDateTime.of(2026, 9, 23, 12, 0), StaffRole.ADMIN));
        authorization = "Bearer " + jwtProvider.issue(staff, Instant.now());
        for (int i = 1; i <= RACERS; i++) {
            tables.add(tableRepository.save(new TableEntity(booth, "R-" + i, "race-tok-" + i)));
        }
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        tables.clear();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void 같은_칸에_동시에_놓으면_한_테이블만_성공한다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                int row = round;
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> results = new ArrayList<>();
                for (TableEntity t : tables) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return mockMvc.perform(patch("/api/v1/admin/tables/{id}/grid-position", t.getId())
                                        .header("Authorization", authorization)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"row\":" + row + ",\"col\":1}"))
                                .andReturn().getResponse().getStatus();
                    }));
                }
                start.countDown();
                int ok = 0;
                for (Future<Integer> f : results) if (f.get() == 200) ok++;
                assertEquals(1, ok, round + "회차: 같은 칸에 성공한 테이블 수");

                long inCell = tableRepository.findAll().stream()
                        .filter(t -> Integer.valueOf(row).equals(t.getGridRow()) && Integer.valueOf(1).equals(t.getGridCol()))
                        .count();
                assertEquals(1, inCell, round + "회차: DB에서 그 칸을 차지한 테이블 수");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
