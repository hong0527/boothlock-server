package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.AlreadyPaidException;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.order.OrderRaceTestFixture;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.service.OrderCancelService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.bearer;
import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 결제 모달 항목 수정·손님 취소·입금 확인 경합 실측 (audit2 B1·B2·B3·M6) — 실제 두 스레드를 같은 순간에 출발시키고
 * 판마다 한쪽에 0~7ms 지연을 번갈아 넣어 양쪽이 먼저 커밋하는 경우를 모두 만든다. 어느 순서든
 * "취소됐는데 PAID"·"입금 확인 응답 금액 ≠ 저장 금액"·"합계 ≠ 남은 항목 합"·500이 없어야 한다. 판마다 새 주문을 쓴다.
 * 끝에는 결정적 테스트로 "입금 확인 트랜잭션이 열려 있는 동안 들어온 수정·취소"가 잠금 뒤에서 409를 받는지도 확인한다.
 */
@SpringBootTest
class OrderEditConcurrencyTests {

    private static final int ROUNDS = 40;

    @Autowired OrderRaceTestFixture fx;
    @Autowired DashboardOrderActionService orderActionService;
    @Autowired OrderCancelService orderCancelService;

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

    /** 결과 문자열: 성공이면 OK(:값), 알려진 거절이면 예외 이름. 그 밖의 예외(500 원인)는 그대로 던져 테스트를 깨뜨린다 */
    private Callable<String> outcome(long delayMillis, CountDownLatch start, Callable<Object> action) {
        return () -> {
            start.await();
            Thread.sleep(delayMillis);
            try {
                Object value = action.call();
                return value == null ? "OK" : "OK:" + value;
            } catch (InvalidStateException | AlreadyPaidException | NotFoundException e) {
                return e.getClass().getSimpleName();
            }
        };
    }

    /** 홀수 판은 첫 작업을, 짝수 판은 두 번째 작업을 0~7ms 늦춘다 */
    private static long firstDelay(int round) {
        return round % 2 == 1 ? round % 8 : 0;
    }

    private static long secondDelay(int round) {
        return round % 2 == 0 ? round % 8 : 0;
    }

    private List<String> race(Callable<String> a, Callable<String> b, CountDownLatch start) throws Exception {
        Future<String> fa = pool.submit(a);
        Future<String> fb = pool.submit(b);
        start.countDown();
        return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
    }

    private static boolean ok(String result) {
        return result.startsWith("OK");
    }

    private static int okValue(String result) {
        return Integer.parseInt(result.substring("OK:".length()));
    }

    private static int liveSum(OrderEntity order) {
        return order.getItems().stream().filter(i -> !i.isCanceled()).mapToInt(OrderItemEntity::subtotal).sum();
    }

    private static void assertNotCanceledAndPaid(OrderEntity order, String context) {
        assertFalse(order.getStatus() == OrderStatus.CANCELED && order.getPaymentStatus() == PaymentStatus.PAID,
                context + " 취소됐는데 입금 확인된 주문");
    }

    private static void assertBothOrdersHappened(int first, int second, String label) {
        System.out.println(label + ": " + first + " / " + second);
        assertTrue(first > 0 && second > 0, label + " 양쪽 순서가 모두 나와야 실측이 의미 있다: " + first + "/" + second);
    }

    // ── 1. 수량 변경 vs O11 입금 확인 (B1·R5) ─────────────────

    @Test
    void qtyChangeRacingPaymentNeverConfirmsUnseenAmount() throws Exception {
        int editedFirst = 0;
        int paidFirst = 0;
        for (int round = 0; round < ROUNDS; round++) {
            fx.openSession();
            Long orderId = fx.manualOrder(fx.kimchiId, 3).orderId();   // 24000
            Long itemId = fx.itemIdOf(orderId, fx.kimchiId);
            CountDownLatch start = new CountDownLatch(1);

            List<String> results = race(
                    outcome(firstDelay(round), start, () -> orderActionService.updateItemQty(bearer(fx.staffToken), orderId, itemId, 1).totalAmount()),
                    outcome(secondDelay(round), start, () -> orderActionService.confirmPayment(bearer(fx.secondStaffToken), orderId, PaymentMethod.CASH).totalAmount()),
                    start);

            OrderEntity order = fx.reload(orderId);
            String ctx = "round " + round + " " + results;
            assertTrue(ok(results.get(1)), ctx + " 입금 확인은 어느 순서든 성공해야 한다");
            // 입금 확인이 응답으로 보여준 금액 == 최종 저장 금액 — 확인 뒤에 금액이 바뀌었다면 운영자는 모르는 금액을 승인한 것
            assertEquals(okValue(results.get(1)), order.getTotalAmount(), ctx + " 입금 확인 후 금액이 바뀜");
            assertEquals(PaymentStatus.PAID, order.getPaymentStatus(), ctx);
            assertEquals(liveSum(order), order.getTotalAmount(), ctx + " 합계 ≠ 항목 합");
            if (ok(results.get(0))) {
                assertEquals(8000, order.getTotalAmount(), ctx + " 수정이 먼저면 입금은 새 금액 기준");
                assertEquals(1, order.getItems().get(0).getQty(), ctx);
                editedFirst++;
            } else {
                assertEquals("InvalidStateException", results.get(0), ctx);
                assertEquals(24000, order.getTotalAmount(), ctx + " 입금이 먼저면 수정은 409·금액 불변");
                assertEquals(3, order.getItems().get(0).getQty(), ctx);
                paidFirst++;
            }
        }
        assertBothOrdersHappened(editedFirst, paidFirst, "qty vs O11 (editedFirst/paidFirst)");
    }

    // ── 2. 마지막 항목 개별 취소 vs O11 입금 확인 (B1·R6) ─────────────────

    @Test
    void lastItemCancelRacingPaymentNeverLeavesCanceledPaidOrder() throws Exception {
        int canceledFirst = 0;
        int paidFirst = 0;
        for (int round = 0; round < ROUNDS; round++) {
            fx.openSession();
            Long orderId = fx.manualOrder(fx.kimchiId, 1).orderId();   // 8000
            Long itemId = fx.itemIdOf(orderId, fx.kimchiId);
            CountDownLatch start = new CountDownLatch(1);

            List<String> results = race(
                    outcome(firstDelay(round), start, () -> orderActionService.cancelItem(bearer(fx.staffToken), orderId, itemId).status()),
                    outcome(secondDelay(round), start, () -> orderActionService.confirmPayment(bearer(fx.secondStaffToken), orderId, PaymentMethod.CASH).totalAmount()),
                    start);

            OrderEntity order = fx.reload(orderId);
            String ctx = "round " + round + " " + results;
            assertEquals(1, results.stream().filter(OrderEditConcurrencyTests::ok).count(), ctx + " 정확히 한쪽만 성공");
            assertNotCanceledAndPaid(order, ctx);
            assertEquals(liveSum(order), order.getTotalAmount(), ctx + " 합계 ≠ 항목 합");
            if (ok(results.get(0))) {
                assertEquals("OK:CANCELED", results.get(0), ctx);
                assertEquals(OrderStatus.CANCELED, order.getStatus(), ctx);
                assertEquals(PaymentStatus.UNPAID, order.getPaymentStatus(), ctx);
                assertEquals(0, order.getTotalAmount(), ctx);
                assertEquals("race-staff", order.getCanceledBy(), ctx);
                assertEquals("InvalidStateException", results.get(1), ctx + " 취소된 주문 입금 확인은 409");
                assertNull(order.getApprovedBy(), ctx);
                canceledFirst++;
            } else {
                assertEquals("InvalidStateException", results.get(0), ctx);
                assertEquals(OrderStatus.RECEIVED, order.getStatus(), ctx);
                assertEquals(PaymentStatus.PAID, order.getPaymentStatus(), ctx);
                assertEquals(8000, okValue(results.get(1)), ctx);
                assertEquals(8000, order.getTotalAmount(), ctx);
                assertFalse(order.getItems().get(0).isCanceled(), ctx + " 입금 뒤 항목이 숨겨지면 안 된다");
                assertNull(order.getCanceledBy(), ctx);
                paidFirst++;
            }
        }
        assertBothOrdersHappened(canceledFirst, paidFirst, "cancelItem vs O11 (canceledFirst/paidFirst)");
    }

    // ── 3. 같은 주문 두 항목 동시 취소 (B2) ─────────────────

    @Test
    void cancelingTwoItemsConcurrentlyEndsCanceledWithConsistentTotal() throws Exception {
        int firstStaffLast = 0;
        int secondStaffLast = 0;
        for (int round = 0; round < ROUNDS; round++) {
            fx.openSession();
            Long orderId = fx.manualOrder(List.of(item(fx.kimchiId, 1), item(fx.colaId, 1))).orderId();   // 13000
            Long kimchiItem = fx.itemIdOf(orderId, fx.kimchiId);
            Long colaItem = fx.itemIdOf(orderId, fx.colaId);
            CountDownLatch start = new CountDownLatch(1);

            List<String> results = race(
                    outcome(firstDelay(round), start, () -> orderActionService.cancelItem(bearer(fx.staffToken), orderId, kimchiItem).status()),
                    outcome(secondDelay(round), start, () -> orderActionService.cancelItem(bearer(fx.secondStaffToken), orderId, colaItem).status()),
                    start);

            OrderEntity order = fx.reload(orderId);
            String ctx = "round " + round + " " + results;
            assertTrue(ok(results.get(0)) && ok(results.get(1)), ctx + " 서로 다른 항목 취소는 둘 다 성공해야 한다");
            // 한쪽은 RECEIVED(항목 하나 남음), 다른 쪽은 CANCELED(마지막 항목) — 둘 다 RECEIVED면 마지막 항목 판정이 어긋난 것
            assertEquals(Set.of("OK:RECEIVED", "OK:CANCELED"), Set.copyOf(results), ctx);
            assertEquals(OrderStatus.CANCELED, order.getStatus(), ctx + " 모든 항목이 취소됐는데 CANCELED가 아님");
            assertEquals(0, order.getTotalAmount(), ctx);
            assertEquals(liveSum(order), order.getTotalAmount(), ctx);
            assertTrue(order.getItems().stream().allMatch(OrderItemEntity::isCanceled), ctx);
            assertEquals(2, order.getItems().size(), ctx + " 항목 행은 지우지 않는다");
            assertEquals("전체 항목 취소", order.getCancelReason(), ctx);
            assertNotNull(order.getCanceledAt(), ctx);
            // 취소자는 마지막 항목을 취소한 쪽
            String expectedBy = results.get(0).equals("OK:CANCELED") ? "race-staff" : "race-staff-2";
            assertEquals(expectedBy, order.getCanceledBy(), ctx);
            if ("race-staff".equals(expectedBy)) {
                firstStaffLast++;
            } else {
                secondStaffLast++;
            }
        }
        assertBothOrdersHappened(firstStaffLast, secondStaffLast, "two-item cancel (staff last / staff-2 last)");
    }

    // ── 4. C5 손님 취소 vs O11 입금 확인 (B3·R1) ─────────────────

    @Test
    void customerCancelRacingPaymentNeverLeavesCanceledPaidOrder() throws Exception {
        int canceledFirst = 0;
        int paidFirst = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Long sessionId = fx.openSession();
            Long orderId = fx.customerOrder(fx.colaId, 1).orderId();
            CountDownLatch start = new CountDownLatch(1);

            List<String> results = race(
                    outcome(firstDelay(round), start, () -> orderCancelService.cancel(orderId, sessionId).status()),
                    outcome(secondDelay(round), start, () -> orderActionService.confirmPayment(bearer(fx.staffToken), orderId, PaymentMethod.CASH).totalAmount()),
                    start);

            OrderEntity order = fx.reload(orderId);
            String ctx = "round " + round + " " + results;
            assertEquals(1, results.stream().filter(OrderEditConcurrencyTests::ok).count(), ctx + " 정확히 한쪽만 성공");
            assertNotCanceledAndPaid(order, ctx);
            if (ok(results.get(0))) {
                assertEquals(OrderStatus.CANCELED, order.getStatus(), ctx);
                assertEquals(PaymentStatus.UNPAID, order.getPaymentStatus(), ctx);
                assertEquals("CUSTOMER", order.getCanceledBy(), ctx);
                assertEquals("InvalidStateException", results.get(1), ctx);
                assertNull(order.getApprovedBy(), ctx);
                canceledFirst++;
            } else {
                assertEquals("InvalidStateException", results.get(0), ctx);
                assertEquals(OrderStatus.RECEIVED, order.getStatus(), ctx);
                assertEquals(PaymentStatus.PAID, order.getPaymentStatus(), ctx);
                assertEquals("race-staff", order.getApprovedBy(), ctx);
                assertNull(order.getCanceledBy(), ctx);
                paidFirst++;
            }
        }
        assertBothOrdersHappened(canceledFirst, paidFirst, "C5 vs O11 (canceledFirst/paidFirst)");
    }

    // ── 5. C5 손님 취소 vs 마지막 항목 개별 취소 (M6·R4) ─────────────────

    @Test
    void customerCancelRacingLastItemCancelKeepsOneAuditRecord() throws Exception {
        int customerWon = 0;
        int staffWon = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Long sessionId = fx.openSession();
            Long orderId = fx.customerOrder(fx.colaId, 1).orderId();
            Long itemId = fx.itemIdOf(orderId, fx.colaId);
            CountDownLatch start = new CountDownLatch(1);

            List<String> results = race(
                    outcome(firstDelay(round), start, () -> orderCancelService.cancel(orderId, sessionId).status()),
                    outcome(secondDelay(round), start, () -> orderActionService.cancelItem(bearer(fx.staffToken), orderId, itemId).status()),
                    start);

            OrderEntity order = fx.reload(orderId);
            String ctx = "round " + round + " " + results;
            assertEquals(1, results.stream().filter(OrderEditConcurrencyTests::ok).count(), ctx + " 둘 다 200이면 취소 기록을 서로 덮어쓴다");
            assertEquals(OrderStatus.CANCELED, order.getStatus(), ctx);
            assertEquals(PaymentStatus.UNPAID, order.getPaymentStatus(), ctx);
            assertNotNull(order.getCanceledAt(), ctx);
            if (ok(results.get(0))) {
                assertEquals("CUSTOMER", order.getCanceledBy(), ctx);
                assertNull(order.getCancelReason(), ctx);
                assertEquals(5000, order.getTotalAmount(), ctx + " 손님 취소는 항목을 숨기지 않는다");
                assertFalse(order.getItems().get(0).isCanceled(), ctx);
                customerWon++;
            } else {
                assertEquals("race-staff", order.getCanceledBy(), ctx);
                assertEquals("전체 항목 취소", order.getCancelReason(), ctx);
                assertEquals(0, order.getTotalAmount(), ctx);
                assertTrue(order.getItems().get(0).isCanceled(), ctx);
                staffWon++;
            }
        }
        assertBothOrdersHappened(customerWon, staffWon, "C5 vs cancelItem (customerWon/staffWon)");
    }

    // ── 결정적 인터리빙: 입금 확인이 커밋되기 전에 들어온 수정·취소는 잠금 뒤에서 최신 상태를 본다 ─────────────────

    /**
     * 입금 확인(O11)의 조건부 UPDATE를 트랜잭션 안에서 실행해 행 잠금을 쥔 채, 다른 스레드에서 action을 시작한다.
     * action은 FOR UPDATE에서 막혀 있어야 하고(잠금이 없으면 옛 UNPAID를 읽고 진행한다), 커밋 뒤에 PAID를 보고 409여야 한다.
     */
    private String runWhilePaymentIsOpen(Long orderId, Callable<Object> action) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                result.set(outcome(0, new CountDownLatch(0), action).call());
            } catch (Exception e) {
                result.set("UNEXPECTED:" + e);
            }
        });
        fx.tx.executeWithoutResult(status -> {
            int updated = fx.orderRepository.markPaid(orderId, fx.booth.getId(), PaymentMethod.CASH, "race-staff-2",
                    LocalDateTime.now(OrderRaceTestFixture.KST));
            assertEquals(1, updated);
            other.start();
            try {
                other.join(300);   // 잠금에 막혀 있어야 한다
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            assertTrue(other.isAlive(), "입금 확인 트랜잭션이 열려 있는데 수정이 끝났다 — 행 잠금이 없다");
        });
        other.join(10_000);
        assertFalse(other.isAlive(), "입금 확인 커밋 뒤에도 수정이 끝나지 않았다");
        return result.get();
    }

    @Test
    void qtyChangeWaitsForOpenPaymentThenSeesPaid() throws Exception {
        fx.openSession();
        Long orderId = fx.manualOrder(fx.kimchiId, 3).orderId();
        Long itemId = fx.itemIdOf(orderId, fx.kimchiId);

        String result = runWhilePaymentIsOpen(orderId,
                () -> orderActionService.updateItemQty(bearer(fx.staffToken), orderId, itemId, 1).totalAmount());

        assertEquals("InvalidStateException", result);
        OrderEntity order = fx.reload(orderId);
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertEquals(24000, order.getTotalAmount());
        assertEquals(3, order.getItems().get(0).getQty());
    }

    @Test
    void lastItemCancelWaitsForOpenPaymentThenSeesPaid() throws Exception {
        fx.openSession();
        Long orderId = fx.manualOrder(fx.kimchiId, 1).orderId();
        Long itemId = fx.itemIdOf(orderId, fx.kimchiId);

        String result = runWhilePaymentIsOpen(orderId,
                () -> orderActionService.cancelItem(bearer(fx.staffToken), orderId, itemId).status());

        assertEquals("InvalidStateException", result);
        OrderEntity order = fx.reload(orderId);
        assertEquals(OrderStatus.RECEIVED, order.getStatus());
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertEquals(8000, order.getTotalAmount());
        assertFalse(order.getItems().get(0).isCanceled());
    }

    @Test
    void customerCancelWaitsForOpenPaymentThenSeesPaid() throws Exception {
        Long sessionId = fx.openSession();
        Long orderId = fx.customerOrder(fx.colaId, 1).orderId();

        String result = runWhilePaymentIsOpen(orderId, () -> orderCancelService.cancel(orderId, sessionId).status());

        assertEquals("InvalidStateException", result);
        OrderEntity order = fx.reload(orderId);
        assertEquals(OrderStatus.RECEIVED, order.getStatus());
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertNull(order.getCanceledBy());
    }
}
