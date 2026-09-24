package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.OrderRateLimitedException;
import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.order.controller.OrderController;
import com.boothlock.boothlock_server.order.dto.OrderCreateRequest;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableStatus;
import com.boothlock.boothlock_server.tableqr.dto.TableCheckoutResponse;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionCreateRequest;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionResponse;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O6 퇴실의 동시성 — 실제 스레드로 퇴실 연타·퇴실+QR 재스캔(C1)·퇴실+손님 요청(C2~C4 인증)·퇴실+주문(C3)을 겹친다.
 * 확인하는 것: ① 유니크 제약(table_id, ended_at_key) 위반·예상 밖 예외가 없다 ② 종료된 세션이 되살아나지 않는다
 * ③ 끝난 뒤 "열린 세션이 있으면 OCCUPIED, 없으면 EMPTY"가 맞는다.
 * 레이스는 매번 같은 순서로 나지 않으므로 라운드를 반복한다. 커밋이 필요해 롤백 없이 직접 정리한다.
 */
@SpringBootTest
class TableCheckoutConcurrencyTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int ROUNDS = 25;

    @Autowired TableAdminService tableAdminService;
    @Autowired TableSessionService tableSessionService;
    @Autowired TableSessionAuthService tableSessionAuthService;
    @Autowired OrderController orderController;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired DailyCounterRepository dailyCounterRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private BoothEntity booth;
    private String authorization;

    @BeforeEach
    void setUp() {
        cleanUp();
        booth = boothRepository.save(new BoothEntity("동시성 부스", "은행 1234", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        StaffAccountEntity staff = staffRepository.save(new StaffAccountEntity(booth, "admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        authorization = "Bearer " + jwtProvider.issue(staff, Instant.now());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        orderRepository.deleteAll();
        dailyCounterRepository.deleteAll();
        menuRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void concurrentCheckoutClicksAllSucceedAndEndSessionOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            TableEntity table = tableRepository.save(new TableEntity(booth, "D" + round, "tok-double-" + round));
            String token = tableSessionService.createOrRestore(new TableSessionCreateRequest(table.getTableToken())).sessionToken();

            List<Object> results = race(4, (i, checkoutDone) -> () -> tableAdminService.checkoutTable(authorization, table.getId(), false));

            // 멱등 — 연타 전부 200이고 status는 EMPTY다. 유니크 위반·500 같은 예상 밖 예외는 없어야 한다
            for (Object result : results) {
                assertTrue(result instanceof TableCheckoutResponse, "퇴실 연타 중 예외: " + result);
                assertEquals(TableStatus.EMPTY, ((TableCheckoutResponse) result).status());
            }
            TableSessionEntity session = tableSessionRepository.findBySessionToken(token).orElseThrow();
            assertNotNull(session.getEndedAt());
            assertEquals(session.getId(), session.getEndedAtKey());
            assertEquals(TableStatus.EMPTY, tableRepository.findById(table.getId()).orElseThrow().getStatus());
        }
    }

    @Test
    void checkoutRacingRescansNeverViolatesUniqueAndLeavesConsistentState() throws Exception {
        AtomicInteger scansDuringRace = new AtomicInteger();
        AtomicInteger createdAfterCheckout = new AtomicInteger();
        for (int round = 0; round < ROUNDS; round++) {
            TableEntity table = tableRepository.save(new TableEntity(booth, "R" + round, "tok-rescan-" + round));
            String oldToken = tableSessionService.createOrRestore(new TableSessionCreateRequest(table.getTableToken())).sessionToken();
            TableSessionCreateRequest scan = new TableSessionCreateRequest(table.getTableToken());

            // 손님 스레드는 퇴실이 끝날 때까지 계속 재스캔하고, 끝난 뒤 한 번 더 찍는다 — 퇴실 전·중·후가 반드시 겹친다
            List<Object> results = race(3, (i, checkoutDone) -> i == 0
                    ? () -> tableAdminService.checkoutTable(authorization, table.getId(), false)
                    : () -> {
                        List<Object> outcomes = new ArrayList<>();
                        while (!checkoutDone.get()) {
                            outcomes.add(tableSessionService.createOrRestore(scan));
                        }
                        outcomes.add(tableSessionService.createOrRestore(scan));
                        return outcomes;
                    });

            for (Object result : results) {
                assertTrue(!(result instanceof Throwable), "퇴실+재스캔 중 예외: " + result);
                if (result instanceof List<?> outcomes) {
                    scansDuringRace.addAndGet(outcomes.size());
                    Object last = outcomes.getLast();
                    // 퇴실 뒤 스캔은 옛 세션을 복원하면 안 된다
                    assertTrue(!oldToken.equals(((TableSessionResponse) last).sessionToken()), "퇴실 후 스캔이 옛 세션을 돌려줬다");
                    if (!((TableSessionResponse) last).restored()) {
                        createdAfterCheckout.incrementAndGet();
                    }
                }
            }
            TableSessionEntity old = tableSessionRepository.findBySessionToken(oldToken).orElseThrow();
            assertNotNull(old.getEndedAt());
            assertEquals(old.getId(), old.getEndedAtKey());

            // 퇴실 뒤 스캔이 반드시 있었으므로 열린 세션은 정확히 1개, status는 OCCUPIED여야 한다
            List<TableSessionEntity> open = tableSessionRepository.findOpenByTableIds(List.of(table.getId()));
            assertEquals(1, open.size());
            assertEquals(TableStatus.OCCUPIED, tableRepository.findById(table.getId()).orElseThrow().getStatus());
        }
        System.out.println("[checkout vs rescan] scans=" + scansDuringRace.get() + ", last scans that created (not restored)=" + createdAfterCheckout.get());
    }

    @Test
    void checkoutRacingCustomerRequestsNeverResurrectsSession() throws Exception {
        AtomicInteger authenticated = new AtomicInteger();
        AtomicInteger expired = new AtomicInteger();
        for (int round = 0; round < ROUNDS; round++) {
            TableEntity table = tableRepository.save(new TableEntity(booth, "C" + round, "tok-customer-" + round));
            String token = tableSessionService.createOrRestore(new TableSessionCreateRequest(table.getTableToken())).sessionToken();

            List<Object> results = race(3, (i, checkoutDone) -> i == 0
                    ? () -> tableAdminService.checkoutTable(authorization, table.getId(), false)
                    : () -> {
                        // C2~C4 폴링 — 퇴실이 끝날 때까지 두드리고 끝난 뒤 한 번 더
                        List<Object> outcomes = new ArrayList<>();
                        boolean lastRound = false;
                        while (true) {
                            lastRound = checkoutDone.get();
                            try {
                                outcomes.add(tableSessionAuthService.authenticate(token));
                            } catch (SessionExpiredException e) {
                                outcomes.add(e);
                            }
                            if (lastRound) {
                                return outcomes;
                            }
                        }
                    });

            for (Object result : results) {
                assertTrue(!(result instanceof Throwable), "퇴실+손님 요청 중 예외: " + result);
                if (result instanceof List<?> outcomes) {
                    for (Object outcome : outcomes) {
                        if (outcome instanceof SessionExpiredException) {
                            expired.incrementAndGet();
                        } else {
                            authenticated.incrementAndGet();
                        }
                    }
                    assertTrue(outcomes.getLast() instanceof SessionExpiredException, "퇴실 완료 뒤 요청이 410이 아니다");
                }
            }
            TableSessionEntity session = tableSessionRepository.findBySessionToken(token).orElseThrow();
            assertNotNull(session.getEndedAt(), "퇴실된 세션이 활동 기록에 덮여 되살아났다");
            assertEquals(session.getId(), session.getEndedAtKey());
            assertEquals(TableStatus.EMPTY, tableRepository.findById(table.getId()).orElseThrow().getStatus());
            assertTrue(isExpired(token));
        }
        System.out.println("[checkout vs customer auth] ok=" + authenticated.get() + ", SESSION_EXPIRED=" + expired.get());
    }

    @Test
    void checkoutRacingOrderCreationNeverFailsWithUnexpectedError() throws Exception {
        MenuEntity menu = menuRepository.save(new MenuEntity(booth, "김치전", 8000, null, null, true));
        AtomicInteger created = new AtomicInteger();
        AtomicInteger expired = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger();
        for (int round = 0; round < ROUNDS; round++) {
            TableEntity table = tableRepository.save(new TableEntity(booth, "O" + round, "tok-order-" + round));
            String token = tableSessionService.createOrRestore(new TableSessionCreateRequest(table.getTableToken())).sessionToken();
            OrderCreateRequest order = new OrderCreateRequest(List.of(new OrderCreateRequest.OrderItemRequest(menu.getId(), 1)));

            List<Object> results = race(3, (i, checkoutDone) -> i == 0
                    ? () -> tableAdminService.checkoutTable(authorization, table.getId(), false)
                    : () -> {
                        List<Object> outcomes = new ArrayList<>();
                        boolean lastRound;
                        do {
                            lastRound = checkoutDone.get();
                            try {
                                outcomes.add(orderController.createOrder(token, UUID.randomUUID().toString(), order));
                            } catch (SessionExpiredException | OrderRateLimitedException e) {
                                outcomes.add(e);
                            }
                        } while (!lastRound);
                        return outcomes;
                    });

            for (Object result : results) {
                if (result instanceof Throwable t) {
                    throw new AssertionError("퇴실+주문 중 예외", t);
                }
                if (result instanceof List<?> outcomes) {
                    for (Object outcome : outcomes) {
                        if (outcome instanceof SessionExpiredException) {
                            expired.incrementAndGet();
                        } else if (outcome instanceof OrderRateLimitedException) {
                            rateLimited.incrementAndGet();
                        } else {
                            created.incrementAndGet();
                        }
                    }
                    assertTrue(outcomes.getLast() instanceof SessionExpiredException, "퇴실 완료 뒤 주문이 410이 아니다");
                }
            }
            TableSessionEntity session = tableSessionRepository.findBySessionToken(token).orElseThrow();
            assertNotNull(session.getEndedAt());
            assertEquals(TableStatus.EMPTY, tableRepository.findById(table.getId()).orElseThrow().getStatus());
        }
        // 인증(C3 1단계)과 주문 저장은 트랜잭션이 갈라져 있어, 인증 통과 직후 퇴실이 커밋되면 주문이 종료된 세션에 붙을 수 있다.
        // 주문 파트 코드(OrderController·OrderWriter)라 이 테스트는 500·예상 밖 예외가 없는지만 본다
        System.out.println("[checkout vs C3] created=" + created.get() + ", SESSION_EXPIRED=" + expired.get()
                + ", rateLimited=" + rateLimited.get());
    }

    /**
     * 되살아남의 원인을 결정적으로 재현한다 — 손님 요청이 세션을 읽은 뒤, 활동 기록을 쓰기 전에 퇴실이 커밋되는 순서.
     * 옛 사본으로 엔티티를 저장해도 변경 컬럼(last_activity_at)만 UPDATE되어야 ended_at·ended_at_key가 보존된다(@DynamicUpdate)
     */
    @Test
    void staleEntityWriteAfterCheckoutDoesNotReopenSession() throws Exception {
        TableEntity table = tableRepository.save(new TableEntity(booth, "S1", "tok-stale"));
        String token = tableSessionService.createOrRestore(new TableSessionCreateRequest(table.getTableToken())).sessionToken();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                TableSessionEntity stale = tableSessionRepository.findBySessionToken(token).orElseThrow();
                try {
                    executor.submit(() -> tableAdminService.checkoutTable(authorization, table.getId(), false)).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                stale.touch(LocalDateTime.now(KST));   // 퇴실 이전에 읽은 사본 — 커밋 시 UPDATE된다
            });
        } finally {
            executor.shutdownNow();
        }

        TableSessionEntity reloaded = tableSessionRepository.findBySessionToken(token).orElseThrow();
        assertNotNull(reloaded.getEndedAt());
        assertEquals(reloaded.getId(), reloaded.getEndedAtKey());
        assertTrue(isExpired(token));
    }

    /**
     * 퇴실 경고의 집계 순서(M2) — 주문 저장 트랜잭션이 세션 행을 조건부 UPDATE로 잠근 채 주문을 넣는 중이면,
     * 퇴실은 그 커밋을 기다렸다가 종료하고, 그 주문을 미결제 경고에 포함해야 한다. 집계를 먼저 하면 이 주문이 경고에서 빠진다
     */
    @Test
    void checkoutWaitsForInFlightOrderAndCountsItInUnpaidWarning() throws Exception {
        TableEntity table = tableRepository.save(new TableEntity(booth, "W1", "tok-warning"));
        String token = tableSessionService.createOrRestore(new TableSessionCreateRequest(table.getTableToken())).sessionToken();
        Long sessionId = tableSessionRepository.findBySessionToken(token).orElseThrow().getId();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TableCheckoutResponse> checkout = tx.execute(status -> {
                // 주문 파트의 저장 트랜잭션과 같은 모양 — 세션 행 조건부 UPDATE(잠금) 뒤 주문 INSERT, 아직 커밋 전
                assertEquals(1, tableSessionRepository.touchIfActive(sessionId, LocalDateTime.now(KST)));
                orderRepository.save(new com.boothlock.boothlock_server.order.domain.OrderEntity(booth.getId(), sessionId, "W1-1",
                        LocalDate.now(KST), 1, "idem-inflight", 8000, false, LocalDateTime.now(KST)));
                orderRepository.flush();
                Future<TableCheckoutResponse> pending = executor.submit(() -> tableAdminService.checkoutTable(authorization, table.getId(), false));
                try {
                    // H2 기본 잠금 대기(1초)보다 짧게 본다 — 퇴실이 이 시간 안에 끝나면 잠금을 기다리지 않은 것이다
                    pending.get(300, TimeUnit.MILLISECONDS);
                    throw new AssertionError("퇴실이 진행 중인 주문 트랜잭션을 기다리지 않고 끝났다");
                } catch (java.util.concurrent.TimeoutException expected) {
                    // 세션 행 잠금에 막혀 대기 중 — 정상
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return pending;
            });
            TableCheckoutResponse response = checkout.get(10, TimeUnit.SECONDS);   // 커밋 뒤 풀린다
            assertTrue(response.unpaidWarning(), "커밋 직전에 끼어든 주문이 미결제 경고에서 빠졌다");
            assertEquals("미결제 주문 1건 있음", response.warning());
        } finally {
            executor.shutdownNow();
        }
        assertNotNull(tableSessionRepository.findBySessionToken(token).orElseThrow().getEndedAt());
        assertTrue(isExpired(token));
    }

    @Test
    void touchIfActiveDoesNothingOnEndedSession() {
        TableEntity table = tableRepository.save(new TableEntity(booth, "S2", "tok-touch"));
        String token = tableSessionService.createOrRestore(new TableSessionCreateRequest(table.getTableToken())).sessionToken();
        tableAdminService.checkoutTable(authorization, table.getId(), false);
        TableSessionEntity ended = tableSessionRepository.findBySessionToken(token).orElseThrow();

        assertEquals(0, tableSessionRepository.touchIfActive(ended.getId(), LocalDateTime.now(KST).plusHours(1)));

        TableSessionEntity reloaded = tableSessionRepository.findBySessionToken(token).orElseThrow();
        assertEquals(ended.getLastActivityAt(), reloaded.getLastActivityAt());
        assertNotNull(reloaded.getEndedAt());
    }

    private boolean isExpired(String token) {
        try {
            tableSessionAuthService.authenticate(token);
            return false;
        } catch (SessionExpiredException e) {
            return true;
        }
    }

    @FunctionalInterface
    private interface TaskFactory {
        Callable<?> create(int index, AtomicBoolean firstTaskDone);
    }

    /**
     * 스레드를 준비시킨 뒤 동시에 출발시켜 결과(반환값 또는 예외)를 모은다.
     * 0번 작업(퇴실)이 끝나면 firstTaskDone이 켜진다 — 나머지 작업은 이를 보고 퇴실 전·중·후를 모두 겹치게 돈다
     */
    private static List<Object> race(int threads, TaskFactory factory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean firstTaskDone = new AtomicBoolean();
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<?> task = factory.create(i, firstTaskDone);
                boolean first = i == 0;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return task.call();
                    } catch (Throwable t) {
                        return t;
                    } finally {
                        if (first) {
                            firstTaskDone.set(true);
                        }
                    }
                }));
            }
            ready.await();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
