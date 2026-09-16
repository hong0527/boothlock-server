package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.order.OrderRaceTestFixture;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.KST;
import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3 주문 저장 vs 퇴실(O6) 경합 (audit2 M2 / gap r3) — 퇴실이 남기는 조건부 UPDATE(ended_at 채움)와 주문 저장을 같은 순간에
 * 출발시킨다. 저장 트랜잭션 안의 세션 생존 확인(touchIfSessionActive)이 세션 행을 잠그므로 두 결과만 허용된다:
 * (a) 주문 201 → 퇴실이 뒤에 커밋됐고, 퇴실 트랜잭션이 잠금을 얻은 뒤 센 미결제 건수에 이 주문이 보인다.
 * (b) 주문 410 → 퇴실이 먼저 커밋됐고 주문 행은 없다.
 * main의 결함은 "퇴실 200(미결제 0건) 뒤 주문 201이 종료된 세션에 붙는" 세 번째 결과였다.
 */
@SpringBootTest
class OrderWriterSessionRaceTests {

    private static final int ROUNDS = 40;

    @Autowired OrderRaceTestFixture fx;
    @Autowired OrderCreateService orderCreateService;
    @Autowired OrderWriter orderWriter;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        fx.setUp();
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws Exception {
        pool.shutdownNow();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        fx.cleanUp();
    }

    /** 퇴실 흉내 — 세션 종료 UPDATE(행 잠금 획득) 뒤 같은 트랜잭션에서 미결제 건수를 센다. "ended:unpaid" 형태 */
    private String checkout(Long sessionId) {
        return fx.tx.execute(status -> {
            int ended = fx.endSessionIfActive(fx.table.getId(), LocalDateTime.now(KST));
            long unpaid = fx.orderRepository.countBySessionIdAndStatusAndPaymentStatus(
                    sessionId, OrderStatus.RECEIVED, PaymentStatus.UNPAID);
            return ended + ":" + unpaid;
        });
    }

    private String order(Long sessionId) {
        try {
            OrderCreateResponse response = fx.customerOrder(sessionId, List.of(item(fx.kimchiId, 1)), UUID.randomUUID().toString());
            return "201:" + response.orderNo();
        } catch (SessionExpiredException e) {
            return "410";
        }
    }

    private static long firstDelay(int round) {
        return round % 2 == 1 ? round % 8 : 0;
    }

    private static long secondDelay(int round) {
        return round % 2 == 0 ? round % 8 : 0;
    }

    private Callable<String> after(long delayMillis, CountDownLatch start, Callable<String> action) {
        return () -> {
            start.await();
            Thread.sleep(delayMillis);
            return action.call();   // 알려진 예외 외에는 그대로 던져 테스트를 깨뜨린다 (500 0건 확인)
        };
    }

    @Test
    void orderRacingCheckoutNeverAttachesToEndedSession() throws Exception {
        int orderFirst = 0;
        int checkoutFirst = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Long sessionId = fx.openSession();
            CountDownLatch start = new CountDownLatch(1);
            Future<String> checkout = pool.submit(after(firstDelay(round), start, () -> checkout(sessionId)));
            Future<String> order = pool.submit(after(secondDelay(round), start, () -> order(sessionId)));
            start.countDown();
            String checkoutResult = checkout.get(30, TimeUnit.SECONDS);
            String orderResult = order.get(30, TimeUnit.SECONDS);
            String ctx = "round " + round + " checkout=" + checkoutResult + " order=" + orderResult;

            assertTrue(checkoutResult.startsWith("1:"), ctx + " 퇴실은 활성 세션 하나를 종료해야 한다");
            long ordersOnSession = fx.orderRepository.findBySessionIdOrderByCreatedAtDescIdDesc(sessionId).size();
            if (orderResult.startsWith("201")) {
                // 주문이 먼저 커밋됐다 — 퇴실이 잠금을 얻은 뒤 센 미결제 건수에 이 주문이 들어 있어야 한다 (main: 0건이면서 201)
                assertEquals("1:1", checkoutResult, ctx + " 퇴실 200인데 미결제 0건으로 세고 주문이 종료된 세션에 붙었다");
                assertEquals(1, ordersOnSession, ctx);
                orderFirst++;
            } else {
                assertEquals("410", orderResult, ctx);
                assertEquals("1:0", checkoutResult, ctx);
                assertEquals(0, ordersOnSession, ctx + " 410인데 주문 행이 생겼다");
                checkoutFirst++;
            }
        }
        System.out.println("C3 vs checkout: orderFirst=" + orderFirst + " checkoutFirst=" + checkoutFirst);
        assertTrue(orderFirst > 0 && checkoutFirst > 0, "양쪽 순서가 모두 나와야 한다: " + orderFirst + "/" + checkoutFirst);
    }

    /** 결정적: 퇴실 UPDATE가 열려 있는 동안 들어온 주문은 세션 행 잠금에 막혀 기다리다가 커밋 뒤 410을 받는다 */
    @Test
    void orderStartedDuringOpenCheckoutWaitsThenGetsGone() throws Exception {
        Long sessionId = fx.openSession();
        AtomicReference<String> result = new AtomicReference<>();
        Thread ordering = new Thread(() -> result.set(order(sessionId)));

        fx.tx.executeWithoutResult(status -> {
            assertEquals(1, fx.endSessionIfActive(fx.table.getId(), LocalDateTime.now(KST)));
            ordering.start();
            try {
                ordering.join(300);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            assertTrue(ordering.isAlive(), "퇴실 트랜잭션이 열려 있는데 주문 저장이 끝났다 — 세션 잠금이 없다");
        });
        ordering.join(10_000);

        assertEquals("410", result.get());
        assertEquals(0, fx.orderRepository.count());
    }

    /** 결정적: 주문 저장 트랜잭션이 세션을 잠근 동안 퇴실 UPDATE는 기다린다 — 퇴실이 세션 종료 뒤 세는 미결제 건수에 주문이 들어간다 */
    @Test
    void checkoutStartedDuringOpenOrderSaveWaitsAndCountsTheOrder() throws Exception {
        Long sessionId = fx.openSession();
        AtomicReference<String> result = new AtomicReference<>();
        Thread checkingOut = new Thread(() -> result.set(checkout(sessionId)));

        fx.tx.executeWithoutResult(status -> {
            // OrderWriter.save가 하는 일을 같은 순서로 — 세션 확인(잠금)·채번·INSERT를 한 트랜잭션에서
            LocalDateTime now = LocalDateTime.now(KST);
            orderWriter.save(new OrderWriter.OrderSpec(fx.booth.getId(), sessionId, "A3", "A-3",
                    UUID.randomUUID().toString(), 8000,
                    List.of(new com.boothlock.boothlock_server.order.domain.OrderItemEntity(fx.kimchiId, "김치전", 8000, 1)),
                    now, false));
            checkingOut.start();
            try {
                checkingOut.join(300);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            assertTrue(checkingOut.isAlive(), "주문 저장 트랜잭션이 열려 있는데 퇴실이 끝났다 — 세션 잠금이 없다");
        });
        checkingOut.join(10_000);
        assertFalse(checkingOut.isAlive());

        assertEquals("1:1", result.get());
        assertEquals(1, fx.orderRepository.count());
    }
}
