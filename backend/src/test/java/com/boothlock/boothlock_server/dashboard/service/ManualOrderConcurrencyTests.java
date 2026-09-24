package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.dashboard.dto.ManualOrderRequest;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.dto.OrderCreateRequest;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderCreateService;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableStatus;
import com.boothlock.boothlock_server.tableqr.dto.AuthenticatedSession;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionCreateRequest;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.service.TableSessionAuthService;
import com.boothlock.boothlock_server.tableqr.service.TableSessionService;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O14 동시성 — 실제 스레드로 같은 순간에 돌진시킨다 (명세서 §2 채번·C1 테이블 단위 세션).
 * 서비스가 트랜잭션 경계를 스스로 관리하므로 테스트는 트랜잭션으로 감싸지 않고 직접 정리한다.
 */
@SpringBootTest
class ManualOrderConcurrencyTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired ManualOrderService manualOrderService;
    @Autowired OrderCreateService orderCreateService;
    @Autowired TableSessionService tableSessionService;
    @Autowired TableSessionAuthService tableSessionAuthService;
    @Autowired OrderRepository orderRepository;
    @Autowired DailyCounterRepository dailyCounterRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired StaffCallRepository staffCallRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired TableRepository tableRepository;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired BoothRepository boothRepository;
    @Autowired BoothJwtProvider jwtProvider;

    private BoothEntity booth;
    private Long menuId;
    private String authorization;

    @BeforeEach
    void setUp() {
        cleanUp();
        booth = boothRepository.save(new BoothEntity("동시성 부스", "카카오뱅크 1234", null));
        menuId = menuRepository.save(new MenuEntity(booth, "김치전", 8000, null, null, true)).getId();
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        StaffAccountEntity staff = staffAccountRepository.save(new StaffAccountEntity(
                booth, "race-staff", hash, LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.STAFF));
        authorization = "Bearer " + jwtProvider.issue(staff, Instant.now());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        staffCallRepository.deleteAll();
        orderRepository.deleteAll();
        dailyCounterRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        menuRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    private ManualOrderRequest manual(Long tableId) {
        return new ManualOrderRequest(tableId, List.of(new OrderCreateRequest.OrderItemRequest(menuId, 1)));
    }

    /** 모든 작업을 같은 순간에 출발시키고, 하나라도 예외면 그 예외로 테스트를 실패시킨다 */
    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return task.call();
            }));
        }
        ready.await();
        start.countDown();
        List<T> results = new ArrayList<>();
        try {
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
            // 시간 초과로 빠져나와도 진행 중 트랜잭션이 정리(@AfterEach) 뒤에 커밋돼 다음 테스트를 오염시키지 않게 기다린다
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
        return results;
    }

    @Test
    void concurrentManualAndCustomerOrdersGetUniqueSequentialNumbers() throws Exception {
        TableEntity manualTable = tableRepository.save(new TableEntity(booth, "A-3", "race-token-a3"));
        TableEntity customerTable = tableRepository.save(new TableEntity(booth, "B-1", "race-token-b1"));
        TableSessionEntity customerSession = tableSessionRepository.save(
                new TableSessionEntity(customerTable, "race-session-b1", LocalDateTime.now(KST)));

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> manualOrderService.create(authorization, null, manual(null)).response().orderNo());
            tasks.add(() -> manualOrderService.create(authorization, null, manual(manualTable.getId())).response().orderNo());
        }
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> orderCreateService.create(booth.getId(), customerSession.getId(), "B-1",
                    UUID.randomUUID().toString(),
                    new OrderCreateRequest(List.of(new OrderCreateRequest.OrderItemRequest(menuId, 1))))
                    .response().orderNo());
        }

        List<String> orderNos = runTogether(tasks);

        assertEquals(15, orderNos.size());
        assertEquals(15, Set.copyOf(orderNos).size(), "주문번호 중복: " + orderNos);
        List<OrderEntity> saved = orderRepository.findAll();
        assertEquals(15, saved.size());
        assertEquals(IntStream.rangeClosed(1, 15).boxed().collect(Collectors.toSet()),
                saved.stream().map(OrderEntity::getOrderSeq).collect(Collectors.toSet()));
        assertEquals(5, orderNos.stream().filter(no -> no.startsWith("M-")).count());
        assertEquals(5, orderNos.stream().filter(no -> no.startsWith("A3-")).count());
        assertEquals(5, orderNos.stream().filter(no -> no.startsWith("B1-")).count());
        // 번호 접두어와 통산번호가 저장값과 일치 — 재시도가 번호만 바꾸고 orderNo를 옛 값으로 남기지 않았는지
        saved.forEach(o -> assertTrue(o.getOrderNo().endsWith("-" + o.getOrderSeq()), o.getOrderNo()));

        // 테이블 지정 수기 주문 5건이 동시에 와도 활성 세션은 하나로 모인다
        Set<Long> manualTableSessions = saved.stream()
                .filter(o -> o.getOrderNo().startsWith("A3-"))
                .map(OrderEntity::getSessionId)
                .collect(Collectors.toSet());
        assertEquals(1, manualTableSessions.size());
        TableSessionEntity active = tableSessionRepository.findByTableIdAndEndedAtIsNull(manualTable.getId()).orElseThrow();
        assertEquals(active.getId(), manualTableSessions.iterator().next());
        assertEquals(TableStatus.OCCUPIED, tableRepository.findById(manualTable.getId()).orElseThrow().getStatus());
    }

    @Test
    void manualOrderRacingCustomerQrScansEndsInOneSharedSession() throws Exception {
        // 인터리빙 기회를 늘리려고 새 테이블로 여러 판 반복한다
        for (int round = 0; round < 8; round++) {
            TableEntity table = tableRepository.save(new TableEntity(booth, "T" + round, "race-scan-token-" + round));

            List<Callable<Long>> tasks = new ArrayList<>();
            tasks.add(() -> {
                String orderNo = manualOrderService.create(authorization, null, manual(table.getId())).response().orderNo();
                return orderRepository.findAll().stream()
                        .filter(o -> o.getOrderNo().equals(orderNo) && o.getSessionId() != null)
                        .filter(o -> o.getTableLabel().equals(table.getLabel()))
                        .findFirst().orElseThrow().getSessionId();
            });
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> {
                    String token = tableSessionService.createOrRestore(
                            new TableSessionCreateRequest(table.getTableToken())).sessionToken();
                    AuthenticatedSession session = tableSessionAuthService.authenticate(token);
                    return session.sessionId();
                });
            }

            List<Long> sessionIds = runTogether(tasks);

            assertEquals(1, Set.copyOf(sessionIds).size(), "round " + round + " 세션이 갈라짐: " + sessionIds);
            assertEquals(1, tableSessionRepository.findAll().stream()
                    .filter(s -> s.getTable().getId().equals(table.getId()))
                    .count());
        }
    }
}
