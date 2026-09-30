package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.dto.TableMoveRequest;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O25 자리 이동의 동시성.
 * <ol>
 *   <li>두 테이블이 서로 점유된 채(A·B 각자 손님) 동시에 반대 방향 이동을 시도하면(A→B, B→A) — 대상이 항상
 *       사용 중이라 애초에 둘 다 성립할 수 없는 요청이다. 이 테스트가 확인하는 건 "이 경우에도 교착 없이
 *       둘 다 제때 409로 끝난다"는 점(TableAdminService.lockTablePairInIdOrder가 두 테이블 행을 항상 id
 *       오름차순으로 잠가서, source/target이 반대인 두 요청이 서로 반대 순서로 잠그지 않게 한다)</li>
 *   <li>테이블 하나(빈 자리)를 서로 다른 두 테이블의 손님이 동시에 노리면 — 정확히 한쪽만 성공하고
 *       나머지는 409(자리 사용 중)다</li>
 * </ol>
 * 레이스는 매번 같은 순서로 나지 않으므로 라운드를 반복한다.
 */
@SpringBootTest
class TableMoveConcurrencyTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int ROUNDS = 20;

    @Autowired TableAdminService tableAdminService;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;

    private BoothEntity booth;
    private String authorization;

    @BeforeEach
    void setUp() {
        cleanUp();
        booth = boothRepository.save(new BoothEntity("이동동시성부스", "은행 1234", null));
        StaffAccountEntity staff = staffRepository.save(new StaffAccountEntity(booth, "admin",
                PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password"),
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        authorization = "Bearer " + jwtProvider.issue(staff, Instant.now());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void oppositeDirectionMovesBetweenTwoOccupiedTablesNeverDeadlockAndBothConflict() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            TableEntity tableA = tableRepository.save(new TableEntity(booth, "X" + round, "tok-x-" + round));
            TableEntity tableB = tableRepository.save(new TableEntity(booth, "Y" + round, "tok-y-" + round));
            tableSessionRepository.save(new TableSessionEntity(tableA, "sess-x-" + round, LocalDateTime.now(KST)));
            tableSessionRepository.save(new TableSessionEntity(tableB, "sess-y-" + round, LocalDateTime.now(KST)));

            Long aId = tableA.getId();
            Long bId = tableB.getId();
            Callable<Object> moveAtoB = () -> tableAdminService.moveSession(authorization, aId, new TableMoveRequest(bId));
            Callable<Object> moveBtoA = () -> tableAdminService.moveSession(authorization, bId, new TableMoveRequest(aId));

            // 둘 다 "대상이 이미 사용 중"이라 성립할 수 없는 요청이다 — 확인하려는 건 성공/실패 개수가 아니라
            // 교착 없이(타임아웃 없이) 제때 끝나고, 나는 예외가 전부 409(InvalidStateException)라는 점뿐이다
            List<Object> results = race(moveAtoB, moveBtoA);
            for (Object r : results) {
                assertTrue(r instanceof InvalidStateException,
                        "라운드 " + round + " — 서로 점유된 테이블끼리의 반대 방향 이동은 둘 다 409여야 한다: " + r);
            }

            List<TableSessionEntity> openA = tableSessionRepository.findOpenByTableIds(List.of(aId));
            List<TableSessionEntity> openB = tableSessionRepository.findOpenByTableIds(List.of(bId));
            assertEquals(1, openA.size(), "라운드 " + round + " — 아무것도 안 바뀌어야 한다");
            assertEquals(1, openB.size(), "라운드 " + round + " — 아무것도 안 바뀌어야 한다");
        }
    }

    @Test
    void twoOccupiedSourcesRacingForSameEmptyTargetOnlyOneWins() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            TableEntity source1 = tableRepository.save(new TableEntity(booth, "P" + round, "tok-p-" + round));
            TableEntity source2 = tableRepository.save(new TableEntity(booth, "Q" + round, "tok-q-" + round));
            TableEntity target = tableRepository.save(new TableEntity(booth, "R" + round, "tok-r-" + round));
            tableSessionRepository.save(new TableSessionEntity(source1, "sess-p-" + round, LocalDateTime.now(KST)));
            tableSessionRepository.save(new TableSessionEntity(source2, "sess-q-" + round, LocalDateTime.now(KST)));
            // target은 빈 자리로 둔다(세션 없음)

            Long s1 = source1.getId();
            Long s2 = source2.getId();
            Long t = target.getId();
            Callable<Object> move1 = () -> tableAdminService.moveSession(authorization, s1, new TableMoveRequest(t));
            Callable<Object> move2 = () -> tableAdminService.moveSession(authorization, s2, new TableMoveRequest(t));

            List<Object> results = race(move1, move2);
            long succeeded = results.stream().filter(r -> !(r instanceof Throwable)).count();
            long conflicted = results.stream().filter(r -> r instanceof InvalidStateException).count();
            for (Object r : results) {
                if (r instanceof Throwable th && !(th instanceof InvalidStateException)) {
                    throw new AssertionError("라운드 " + round + " — 예상 밖 예외: " + th, th);
                }
            }
            assertEquals(1, succeeded, "라운드 " + round + " — 빈 자리는 한쪽만 차지해야 한다: " + results);
            assertEquals(1, conflicted, "라운드 " + round + " — 나머지 한쪽은 409여야 한다: " + results);

            // 진 쪽 소스는 여전히 자기 세션을 갖고 있어야 하고(아무것도 안 바뀜), target은 정확히 하나의 세션을 얻는다
            List<TableSessionEntity> openTarget = tableSessionRepository.findOpenByTableIds(List.of(t));
            assertEquals(1, openTarget.size(), "라운드 " + round);
            long stillOpenSources = List.of(s1, s2).stream()
                    .filter(id -> !tableSessionRepository.findOpenByTableIds(List.of(id)).isEmpty())
                    .count();
            assertEquals(1, stillOpenSources, "라운드 " + round + " — 이긴 쪽의 원래 자리는 비어야 하고 진 쪽만 세션이 남아야 한다");
        }
    }

    private static List<Object> race(Callable<Object> task1, Callable<Object> task2) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : List.of(task1, task2)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return task.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
