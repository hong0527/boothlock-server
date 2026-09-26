package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.dashboard.PosTestFixture;
import com.boothlock.boothlock_server.dashboard.dto.TablePaymentRequest;
import com.boothlock.boothlock_server.dashboard.repository.TablePaymentOrderRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.AlreadyPaidException;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.service.OrderCancelService;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.boothlock.boothlock_server.dashboard.PosTestFixture.bearer;
import static com.boothlock.boothlock_server.dashboard.PosTestFixture.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O24 동시성 실측 — 실제 두 스레드를 같은 순간(판마다 조금씩 시차를 두어)에 출발시키고 판마다 새 세션·주문을 쓴다.
 * O24끼리·O24 vs O11은 양쪽 모두 행 잠금/조건부 UPDATE라 불변식을 단언한다.
 * O24 vs C5(손님 취소)·O24 vs 항목 수정은 상대편(주문 파트)이 아직 잠금 없이 조회→수정하는 main 코드라 모순이 날 수 있다 —
 * 여기서는 500이 없는지만 단언하고 모순 횟수를 세어 출력한다(보고용). 주문 파트의 잠금이 들어오면 아래 TODO 단언을 켠다.
 */
@SpringBootTest
class PosPaymentConcurrencyTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int ROUNDS = 40;

    @Autowired PosTestFixture fx;
    @Autowired TablePaymentService tablePaymentService;
    @Autowired DashboardOrderActionService orderActionService;
    @Autowired OrderCancelService orderCancelService;
    @Autowired TablePaymentOrderRepository targetRepository;
    @Autowired TransactionTemplate transactionTemplate;

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

    /** 결과 문자열: 성공이면 OK, 알려진 거절이면 예외 이름. 그 밖의 예외(500 원인)는 그대로 던져 테스트를 깨뜨린다 */
    private Callable<String> outcome(long delayMillis, CountDownLatch start, Runnable action) {
        return () -> {
            start.await();
            Thread.sleep(delayMillis);
            try {
                action.run();
                return "OK";
            } catch (InvalidStateException | AlreadyPaidException e) {
                return e.getClass().getSimpleName();
            }
        };
    }

    /** 짝수 판은 두 번째 작업을, 홀수 판은 첫 번째 작업을 0~7ms 늦춰 양쪽이 먼저 커밋하는 경우를 모두 만든다 */
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

    private OrderEntity reload(Long orderId) {
        return fx.orderRepository.findByIdAndBoothId(orderId, fx.booth.getId()).orElseThrow();
    }

    /** 판마다 테이블 세션을 비우고 새로 시작한다 — 앞 판 주문이 일괄 결제 대상에 섞이지 않게 */
    private void freshSession() {
        fx.tableSessionRepository.findByTableIdAndEndedAtIsNull(fx.table.getId()).ifPresent(session -> {
            session.end(LocalDateTime.now(KST));
            fx.tableSessionRepository.save(session);
        });
    }

    private TablePaymentRequest payRequest(int expectedTotal) {
        return new TablePaymentRequest(fx.table.getId(), expectedTotal, PaymentMethod.BANK_TRANSFER);
    }

    @Test
    void tablePaymentWaitsForRowLockHeldByAnotherTransaction() throws Exception {
        // 잠금이 실제로 걸리는지 결정적으로 본다 — 다른 트랜잭션이 대상 주문을 FOR UPDATE로 잡고 있는 동안 O24는 끝나면 안 된다.
        // (조건부 UPDATE만으로도 중복 승인은 막히므로 경합 테스트는 @Lock을 지워도 통과한다 — 이 테스트가 그 뮤테이션을 잡는다)
        fx.manualOrder(fx.table, fx.kimchiId, 1);
        fx.approvedCustomerOrder(fx.colaId, 1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            assertEquals(2, targetRepository.findUnpaidOfActiveTableSessionsForUpdate(fx.booth.getId(), fx.table.getId()).size());
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(locked.await(10, TimeUnit.SECONDS));

        Future<String> payer = pool.submit(() -> {
            tablePaymentService.confirmTablePayment(bearer(fx.staffToken), payRequest(13000));
            return "OK";
        });
        Thread.sleep(300);
        assertFalse(payer.isDone(), "다른 트랜잭션이 대상 주문을 잠근 동안 일괄 입금이 끝났다 — 행 잠금이 걸리지 않음");

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertEquals("OK", payer.get(10, TimeUnit.SECONDS));
        assertEquals(2, fx.orderRepository.findAll().stream().filter(o -> o.getPaymentStatus() == PaymentStatus.PAID).count());
    }

    @Test
    void doubleClickOnTablePaymentApprovesEachOrderOnce() throws Exception {
        int firstWins = 0;
        for (int round = 0; round < ROUNDS; round++) {
            freshSession();
            Long a = fx.manualOrder(fx.table, fx.kimchiId, 1).orderId();
            Long b = fx.approvedCustomerOrder(fx.colaId, 2).orderId();
            CountDownLatch start = new CountDownLatch(1);

            List<String> results = race(
                    outcome(firstDelay(round), start, () -> tablePaymentService.confirmTablePayment(bearer(fx.staffToken), payRequest(18000))),
                    outcome(secondDelay(round), start, () -> tablePaymentService.confirmTablePayment(bearer(fx.secondStaffToken), payRequest(18000))),
                    start);

            assertEquals(1, results.stream().filter("OK"::equals).count(), "round " + round + " " + results);
            String winner = results.get(0).equals("OK") ? "pos-staff" : "pos-staff-2";
            if (results.get(0).equals("OK")) {
                firstWins++;
            }
            for (Long id : List.of(a, b)) {
                OrderEntity order = reload(id);
                assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
                assertEquals(winner, order.getApprovedBy(), "round " + round + " 승인 기록이 섞임");   // 한 트랜잭션이 두 주문을 모두 승인
            }
        }
        System.out.println("[O24 x O24] rounds=" + ROUNDS + " firstWins=" + firstWins + " secondWins=" + (ROUNDS - firstWins));
    }

    @Test
    void tablePaymentRacingCompleteAlwaysEndsDoneAndPaidWithoutDoubleApproval() throws Exception {
        // O24(일괄 입금)와 O12(완료 처리)는 서로 다른 축을 바꾼다 — 어느 쪽이 먼저 커밋되든 둘 다 성공하고 결과는 DONE·PAID 하나여야 한다.
        // 완료가 먼저면 대상이 DONE·UNPAID가 되는데, 미결제 정의(UnpaidOrderRule)가 DONE을 포함하므로 O24가 그 주문을 놓치지 않는다.
        int contradictions = 0;
        for (int round = 0; round < ROUNDS; round++) {
            freshSession();
            Long a = fx.manualOrder(fx.table, fx.kimchiId, 1).orderId();   // 8000
            Long b = fx.approvedCustomerOrder(fx.colaId, 1).orderId();             // 5000 — 완료 처리 대상
            CountDownLatch start = new CountDownLatch(1);

            List<String> results = race(
                    outcome(firstDelay(round), start, () -> tablePaymentService.confirmTablePayment(bearer(fx.staffToken), payRequest(13000))),
                    outcome(secondDelay(round), start, () -> orderActionService.complete(bearer(fx.secondStaffToken), b)),
                    start);

            assertEquals(List.of("OK", "OK"), results, "round " + round);
            OrderEntity orderA = reload(a);
            OrderEntity orderB = reload(b);
            if (orderB.getStatus() != OrderStatus.DONE || orderA.getStatus() != OrderStatus.RECEIVED
                    || orderA.getPaymentStatus() != PaymentStatus.PAID || orderB.getPaymentStatus() != PaymentStatus.PAID) {
                contradictions++;
            }
            for (OrderEntity order : List.of(orderA, orderB)) {
                assertEquals(PaymentStatus.PAID, order.getPaymentStatus(), "round " + round);
                assertEquals("pos-staff", order.getApprovedBy(), "round " + round + " 승인자");   // 승인은 O24 한 번뿐
                assertEquals(PaymentMethod.BANK_TRANSFER, order.getPaymentMethod());
            }
        }
        assertEquals(0, contradictions, "상태 모순 판 수");
        System.out.println("[O24 x O12] rounds=" + ROUNDS + " contradictions=" + contradictions);
    }

    @Test
    void tablePaymentRacingSinglePaymentNeverDoubleApproves() throws Exception {
        int bulkFirst = 0;
        for (int round = 0; round < ROUNDS; round++) {
            freshSession();
            Long a = fx.manualOrder(fx.table, fx.kimchiId, 1).orderId();   // 8000
            Long b = fx.approvedCustomerOrder(fx.colaId, 1).orderId();             // 5000
            CountDownLatch start = new CountDownLatch(1);

            List<String> results = race(
                    outcome(firstDelay(round), start, () -> tablePaymentService.confirmTablePayment(bearer(fx.staffToken), payRequest(13000))),
                    outcome(secondDelay(round), start, () -> orderActionService.confirmPayment(bearer(fx.secondStaffToken), b, PaymentMethod.CASH)),
                    start);

            OrderEntity orderA = reload(a);
            OrderEntity orderB = reload(b);
            assertEquals(1, results.stream().filter("OK"::equals).count(), "round " + round + " " + results);
            assertEquals(PaymentStatus.PAID, orderB.getPaymentStatus());
            if (results.get(0).equals("OK")) {
                bulkFirst++;
                assertEquals("AlreadyPaidException", results.get(1));
                assertEquals("pos-staff", orderB.getApprovedBy());
                assertEquals(PaymentStatus.PAID, orderA.getPaymentStatus());
            } else {
                // 개별 입금이 먼저 — 일괄 결제는 합계가 달라져(13000→8000) 409, A는 그대로 미결제
                assertEquals("InvalidStateException", results.get(0));
                assertEquals("pos-staff-2", orderB.getApprovedBy());
                assertEquals(PaymentMethod.CASH, orderB.getPaymentMethod());
                assertEquals(PaymentStatus.UNPAID, orderA.getPaymentStatus());
            }
        }
        System.out.println("[O24 x O11] rounds=" + ROUNDS + " bulkFirst=" + bulkFirst + " singleFirst=" + (ROUNDS - bulkFirst));
    }

    @Test
    void tablePaymentRacingItemEditReportsUnseenAmountConfirmations() throws Exception {
        int bulkOk = 0;
        int editOk = 0;
        int violations = 0;   // PAID인데 저장 금액이 운영자가 확인한 21000과 다르다
        for (int round = 0; round < ROUNDS; round++) {
            freshSession();
            Long a = fx.manualOrder(fx.table, List.of(item(fx.kimchiId, 2), item(fx.colaId, 1))).orderId();   // 21000
            Long kimchiItem = reload(a).getItems().stream().filter(i -> i.getMenuId().equals(fx.kimchiId))
                    .findFirst().orElseThrow().getId();
            CountDownLatch start = new CountDownLatch(1);

            List<String> results = race(
                    outcome(firstDelay(round), start, () -> tablePaymentService.confirmTablePayment(bearer(fx.staffToken), payRequest(21000))),
                    outcome(secondDelay(round), start, () -> orderActionService.updateItemQty(bearer(fx.secondStaffToken), a, kimchiItem, 1)),
                    start);

            OrderEntity order = reload(a);
            if (results.get(0).equals("OK")) {
                bulkOk++;
                // O24가 확인한 금액은 잠근 행의 합계라 항상 21000이었다 — 그 뒤 수정이 덮어쓰면 위반
                if (order.getPaymentStatus() == PaymentStatus.PAID && order.getTotalAmount() != 21000) {
                    violations++;
                }
            } else {
                // 수정이 먼저 커밋되면 합계가 13000이라 O24는 409·주문은 미결제 그대로
                assertEquals("InvalidStateException", results.get(0), "round " + round + " " + results);
                assertEquals(PaymentStatus.UNPAID, order.getPaymentStatus(), "round " + round + " 모르는 금액을 확인하면 안 된다");
            }
            if (results.get(1).equals("OK")) {
                editOk++;
            }
        }
        System.out.println("[O24 x 항목수정(main, 주문 행 잠금 없음)] rounds=" + ROUNDS + " bulkOk=" + bulkOk
                + " editOk=" + editOk + " bothOk=" + (bulkOk + editOk - ROUNDS) + " violations(PAID but total!=21000)=" + violations);
        // TODO(port-order 통합 후 활성화): assertEquals(0, violations) — 항목 수정이 주문 행을 잠그면 둘 다 OK인 판이 사라진다
    }

    @Test
    void tablePaymentRacingCustomerCancelReportsCanceledPaidOrders() throws Exception {
        int bulkOk = 0;
        int cancelOk = 0;
        int canceledAndPaid = 0;
        for (int round = 0; round < ROUNDS; round++) {
            freshSession();
            Long a = fx.manualOrder(fx.table, fx.kimchiId, 1).orderId();   // 8000
            Long b = fx.approvedCustomerOrder(fx.colaId, 1).orderId();             // 5000
            TableSessionEntity session = fx.activeSession();
            CountDownLatch start = new CountDownLatch(1);

            List<String> results = race(
                    outcome(firstDelay(round), start, () -> tablePaymentService.confirmTablePayment(bearer(fx.staffToken), payRequest(13000))),
                    outcome(secondDelay(round), start, () -> orderCancelService.cancel(b, session.getId())),
                    start);

            OrderEntity orderA = reload(a);
            OrderEntity orderB = reload(b);
            if (results.get(0).equals("OK")) {
                bulkOk++;
                assertEquals(PaymentStatus.PAID, orderA.getPaymentStatus());
            } else {
                // 취소가 먼저 커밋되면 대상 합계가 8000으로 줄어 O24는 409, A는 미결제 그대로
                assertEquals("InvalidStateException", results.get(0), "round " + round + " " + results);
                assertEquals(PaymentStatus.UNPAID, orderA.getPaymentStatus());
                assertEquals(OrderStatus.CANCELED, orderB.getStatus());
            }
            if (results.get(1).equals("OK")) {
                cancelOk++;
            }
            if (orderB.getStatus() == OrderStatus.CANCELED && orderB.getPaymentStatus() == PaymentStatus.PAID) {
                canceledAndPaid++;
            }
        }
        System.out.println("[O24 x C5(main, 주문 행 잠금 없음)] rounds=" + ROUNDS + " bulkOk=" + bulkOk + " cancelOk=" + cancelOk
                + " bothOk=" + (bulkOk + cancelOk - ROUNDS) + " CANCELED+PAID=" + canceledAndPaid);
        assertTrue(bulkOk + cancelOk >= ROUNDS, "어느 한쪽은 성공해야 한다");
        // TODO(port-order 통합 후 활성화): assertEquals(0, canceledAndPaid) — C5가 주문 행을 잠그면 "취소됐는데 PAID"가 사라진다
    }
}
