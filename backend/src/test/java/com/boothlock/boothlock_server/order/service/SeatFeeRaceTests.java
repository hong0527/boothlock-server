package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.dashboard.service.DashboardOrderActionService;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.OrderRaceTestFixture;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemType;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.dto.OrderCreateRequest;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.bearer;
import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 자릿세(파일럿) — 첫 메뉴 주문 때 따로 만드는 자릿세 주문(OrderWriter.save)의 "세션에 한 번만" 규칙을 동시성·취소(면제)·
 * 되돌리기·유휴 인계까지 확인한다. 같은 테이블 폰 두 대가 첫 주문을 동시에 넣으면, 판정을 잠금 밖에서 할 때 둘 다 "아직 없음"을
 * 보고 자릿세가 두 번 생긴다(로컬 MySQL 8.0 READ COMMITTED에서 10회 중 10회). 판정은 세션 행을 잠근 저장 트랜잭션 안에서 한다.
 */
@SpringBootTest
class SeatFeeRaceTests {

    private static final int ROUNDS = 30;
    private static final int PARTY_SIZE = 4;
    private static final int FEE = 3000;   // 부스 기본값

    @Autowired OrderRaceTestFixture fx;
    @Autowired OrderCreateService orderCreateService;
    @Autowired OrderCancelService orderCancelService;
    @Autowired DashboardOrderActionService dashboardOrderActionService;

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

    /** 인원을 고른 손님의 메뉴 주문(C3) — 이 세션 첫 주문이면 자릿세 주문이 함께 생긴다 */
    private OrderCreateResponse menuOrder(Long sessionId) {
        return orderCreateService.create(fx.booth.getId(), sessionId, fx.table.getLabel(), UUID.randomUUID().toString(),
                new OrderCreateRequest(List.of(item(fx.kimchiId, 1))), PARTY_SIZE).response();
    }

    /** 세션의 자릿세 주문들(취소된 것 포함) */
    private List<OrderEntity> seatFeeOrders(Long sessionId) {
        return fx.tx.execute(status -> fx.orderRepository.findBySessionIdOrderByCreatedAtDescIdDesc(sessionId).stream()
                .filter(o -> o.getItems().stream().anyMatch(i -> i.getItemType() == OrderItemType.SEAT_FEE))
                .toList());
    }

    /** 세션에서 청구된(취소 안 된 주문의 취소 안 된) 자릿세 항목 수 */
    private long chargedSeatFees(Long sessionId) {
        return fx.tx.execute(status -> fx.orderRepository.findBySessionIdOrderByCreatedAtDescIdDesc(sessionId).stream()
                .filter(o -> o.getStatus() != OrderStatus.CANCELED)
                .map(OrderEntity::getItems)
                .flatMap(List::stream)
                .filter(i -> i.getItemType() == OrderItemType.SEAT_FEE && !i.isCanceled())
                .count());
    }

    private OrderEntity onlySeatFeeOrder(Long sessionId) {
        List<OrderEntity> fees = seatFeeOrders(sessionId);
        assertEquals(1, fees.size());
        return fees.getFirst();
    }

    private void concurrentFirstOrders(Long sessionId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Future<OrderCreateResponse> a = pool.submit(() -> { start.await(); return menuOrder(sessionId); });
        Future<OrderCreateResponse> b = pool.submit(() -> { start.await(); return menuOrder(sessionId); });
        start.countDown();
        a.get(30, TimeUnit.SECONDS);
        b.get(30, TimeUnit.SECONDS);
    }

    @Test
    void twoPhonesPlacingFirstOrderAtOnceAreChargedSeatFeeOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Long sessionId = fx.openSession();

            concurrentFirstOrders(sessionId);

            assertEquals(1, seatFeeOrders(sessionId).size(), "round " + round + " 자릿세 주문은 세션에 하나만");
            assertEquals(1, chargedSeatFees(sessionId), "round " + round);
        }
    }

    @Test
    void firstOrderCreatesSeparateDoneUnpaidSeatFeeOrderAndLaterOrdersDoNot() {
        Long sessionId = fx.openSession();
        OrderCreateResponse first = menuOrder(sessionId);

        assertEquals(8000, first.totalAmount(), "메뉴 주문에는 자릿세가 섞이지 않는다");
        OrderEntity seatFee = onlySeatFeeOrder(sessionId);
        assertEquals(OrderStatus.DONE, seatFee.getStatus(), "조리할 것이 없어 주방 대기열·승인대기에 뜨지 않는다");
        assertEquals(PaymentStatus.UNPAID, seatFee.getPaymentStatus());
        assertEquals(FEE * PARTY_SIZE, seatFee.getTotalAmount());

        assertEquals(8000, menuOrder(sessionId).totalAmount());
        assertEquals(1, seatFeeOrders(sessionId).size(), "두 번째 주문엔 자릿세 주문이 다시 생기지 않는다");
    }

    @Test
    void customerCannotCancelSeatFeeOrder() {
        Long sessionId = fx.openSession();
        menuOrder(sessionId);
        OrderEntity seatFee = onlySeatFeeOrder(sessionId);

        assertThrows(InvalidStateException.class, () -> orderCancelService.cancel(seatFee.getId(), sessionId));
        assertEquals(1, chargedSeatFees(sessionId));
    }

    @Test
    void staffCanceledSeatFeeIsWaivedAndNeverChargedAgain() {
        Long sessionId = fx.openSession();
        menuOrder(sessionId);
        dashboardOrderActionService.cancelByStaff(bearer(fx.staffToken), onlySeatFeeOrder(sessionId).getId(), "자릿세 면제");

        menuOrder(sessionId);

        assertEquals(1, seatFeeOrders(sessionId).size(), "운영자 취소는 면제 — 다음 주문에 새로 만들지 않는다");
        assertEquals(0, chargedSeatFees(sessionId));
    }

    @Test
    void restoringStaffCanceledSeatFeeOrderBringsItBackAsDone() {
        Long sessionId = fx.openSession();
        menuOrder(sessionId);
        Long seatFeeId = onlySeatFeeOrder(sessionId).getId();
        String staff = bearer(fx.staffToken);
        dashboardOrderActionService.cancelByStaff(staff, seatFeeId, "실수로 취소");

        DashboardResponse.OrderSummary restored = dashboardOrderActionService.restore(staff, seatFeeId);

        assertEquals(OrderStatus.DONE, restored.status(), "자릿세 주문은 접수가 아니라 처음 상태인 완료로 돌아온다");
        assertEquals(1, chargedSeatFees(sessionId));
    }

    @Test
    void doneSeatFeeOrderCannotBeRestoredToReceived() {
        Long sessionId = fx.openSession();
        menuOrder(sessionId);

        assertThrows(InvalidStateException.class,
                () -> dashboardOrderActionService.restore(bearer(fx.staffToken), onlySeatFeeOrder(sessionId).getId()));
    }

    @Test
    void restoringPaidThenCanceledSeatFeeOrderIsRejected() {
        Long sessionId = fx.openSession();
        menuOrder(sessionId);
        Long seatFeeId = onlySeatFeeOrder(sessionId).getId();
        String staff = bearer(fx.staffToken);
        dashboardOrderActionService.confirmPayment(staff, seatFeeId, PaymentMethod.BANK_TRANSFER);   // 돈을 받았다
        dashboardOrderActionService.cancelByStaff(staff, seatFeeId, "실수로 취소");                    // REFUND_NEEDED

        assertThrows(InvalidStateException.class, () -> dashboardOrderActionService.restore(staff, seatFeeId));
        assertEquals(FEE * PARTY_SIZE, fx.reload(seatFeeId).getTotalAmount(), "받은 돈이 걸린 주문 금액은 그대로");
    }

    // ── 유휴 인계 이어받기(v0.6.13) + 동시성 ─────────────────────────

    /**
     * C1 유휴 재스캔이 남기는 모양 그대로 세션을 바꾼다 — 옛 세션 ended_at과 새 세션 started_at에 같은 시각.
     * 테이블 파트 서비스(TableSessionWriter)는 테이블 잠금·유휴 판정까지 하므로, 여기서는 시각 모양만 흉내 낸다
     */
    private Long idleHandoff() {
        // 1ms 뒤로 — 시계가 거친 환경(Windows)에서 now()가 fx.openSession이 앞 라운드 세션을 닫은 시각과 같게 나오면, 새 세션이
        // 그 무관한 세션까지 인계 선행으로 잡아 그 자릿세를 이어받는다(테스트 구성의 우연 — 실제 C1은 테이블 잠금 아래 한 번만 닫는다)
        LocalDateTime at = LocalDateTime.now(OrderRaceTestFixture.KST).truncatedTo(ChronoUnit.MICROS).plus(1, ChronoUnit.MILLIS);
        fx.tx.execute(st -> fx.endSessionIfActive(fx.table.getId(), at));
        return fx.tableSessionRepository.save(
                new TableSessionEntity(fx.table, "handoff-" + UUID.randomUUID(), at)).getId();
    }

    @Test
    void afterIdleHandoffWithoutPredecessorFeeTwoPhonesAreChargedOnce() throws Exception {
        for (int round = 0; round < ROUNDS / 3; round++) {
            fx.openSession();   // 앞 세션은 주문 없이 끝났다 — 이어받을 자릿세가 없다
            Long after = idleHandoff();

            concurrentFirstOrders(after);

            assertEquals(1, seatFeeOrders(after).size(), "round " + round + " 새 세션 자릿세는 한 번만");
        }
    }

    @Test
    void afterIdleHandoffWithInheritedFeeTwoPhonesAreNotChargedAtAll() throws Exception {
        for (int round = 0; round < ROUNDS / 3; round++) {
            Long before = fx.openSession();
            menuOrder(before);   // 앞 세션이 자릿세를 냈다
            Long after = idleHandoff();

            concurrentFirstOrders(after);

            assertEquals(0, seatFeeOrders(after).size(), "round " + round + " 앞 세션 자릿세를 이어받아 새로 만들지 않는다");
        }
    }

    @Test
    void waivedPredecessorFeeIsInheritedSoIdleRescanDoesNotChargeAgain() {
        // 면제받은 일행이 유휴 임계를 넘겨 다시 찍어도 면제는 이어진다
        Long before = fx.openSession();
        menuOrder(before);
        dashboardOrderActionService.cancelByStaff(bearer(fx.staffToken), onlySeatFeeOrder(before).getId(), "자릿세 면제");
        Long after = idleHandoff();

        menuOrder(after);

        assertEquals(0, seatFeeOrders(after).size());
    }
}
