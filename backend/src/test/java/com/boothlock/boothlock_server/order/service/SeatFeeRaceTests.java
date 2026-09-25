package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.dashboard.service.DashboardOrderActionService;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.order.OrderRaceTestFixture;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemType;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.dto.OrderCreateRequest;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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

/**
 * 자릿세(파일럿) — "세션에 한 번만" 규칙을 동시성과 취소까지 확인한다.
 * 같은 테이블 폰 두 대가 첫 주문을 동시에 넣으면, 판정을 잠금 밖에서 할 때 둘 다 "아직 없음"을 보고 자릿세가 두 번 붙었다
 * (로컬 MySQL 8.0 READ COMMITTED에서 10회 중 10회). 판정은 세션 행을 잠근 저장 트랜잭션(OrderWriter.save) 안에서 한다.
 */
@SpringBootTest
class SeatFeeRaceTests {

    private static final int ROUNDS = 30;
    private static final int PARTY_SIZE = 4;

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

    private OrderCreateResponse order(Long sessionId) {
        return orderCreateService.create(fx.booth.getId(), sessionId, fx.table.getLabel(), UUID.randomUUID().toString(),
                new OrderCreateRequest(List.of(item(fx.kimchiId, 1))), PARTY_SIZE).response();
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

    @Test
    void twoPhonesPlacingFirstOrderAtOnceAreChargedSeatFeeOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Long sessionId = fx.openSession();
            CountDownLatch start = new CountDownLatch(1);
            Future<OrderCreateResponse> a = pool.submit(() -> { start.await(); return order(sessionId); });
            Future<OrderCreateResponse> b = pool.submit(() -> { start.await(); return order(sessionId); });
            start.countDown();
            int total = a.get(30, TimeUnit.SECONDS).totalAmount() + b.get(30, TimeUnit.SECONDS).totalAmount();

            assertEquals(1, chargedSeatFees(sessionId), "round " + round + " 자릿세는 세션에 한 번만");
            assertEquals(8000 * 2 + 3000 * PARTY_SIZE, total, "round " + round + " 두 주문 합계에 자릿세가 한 번만 들어가야 한다");
        }
    }

    @Test
    void canceledFirstOrderDoesNotSwallowSeatFee() {
        Long sessionId = fx.openSession();
        OrderCreateResponse first = order(sessionId);
        assertEquals(8000 + 3000 * PARTY_SIZE, first.totalAmount());

        orderCancelService.cancel(first.orderId(), sessionId);   // 손님이 첫 주문을 취소(C5)
        OrderCreateResponse next = order(sessionId);

        assertEquals(8000 + 3000 * PARTY_SIZE, next.totalAmount(), "취소된 주문의 자릿세는 청구된 것이 아니다 — 다음 주문에 붙어야 한다");
        assertEquals(1, chargedSeatFees(sessionId));
        assertEquals(8000, order(sessionId).totalAmount(), "그다음 주문엔 다시 붙지 않는다");
    }

    /** 주문의 메뉴(MENU) 항목 id */
    private Long menuItemId(Long orderId) {
        return fx.tx.execute(status -> fx.orderRepository.findById(orderId).orElseThrow().getItems().stream()
                .filter(i -> i.getItemType() == OrderItemType.MENU)
                .findFirst().orElseThrow().getId());
    }

    @Test
    void cancelingLastMenuItemCancelsOrderWithSeatFeeAndNextOrderIsChargedAgain() {
        Long sessionId = fx.openSession();
        OrderCreateResponse first = order(sessionId);

        // 결제 모달에서 첫 주문의 유일한 메뉴를 개별 취소 — 예전엔 자릿세만 남은 접수 주문이 주방 대기열에 남았다
        DashboardResponse.OrderSummary after = dashboardOrderActionService.cancelItem(
                bearer(fx.staffToken), first.orderId(), menuItemId(first.orderId()));

        assertEquals(OrderStatus.CANCELED, after.status(), "메뉴가 다 취소되면 주문도 취소된다");
        assertEquals(0, chargedSeatFees(sessionId));
        assertEquals(8000 + 3000 * PARTY_SIZE, order(sessionId).totalAmount(), "다음 주문에 자릿세가 다시 붙는다");
    }

    @Test
    void restoringCanceledSeatFeeOrderDoesNotDoubleCharge() {
        Long sessionId = fx.openSession();
        OrderCreateResponse first = order(sessionId);
        orderCancelService.cancel(first.orderId(), sessionId);
        order(sessionId);   // 자릿세가 여기 다시 붙었다

        DashboardResponse.OrderSummary restored = dashboardOrderActionService.restore(bearer(fx.staffToken), first.orderId());

        assertEquals(OrderStatus.RECEIVED, restored.status());
        assertEquals(1, chargedSeatFees(sessionId), "되살린 주문의 자릿세는 빠져야 한다");
        int restoredTotal = fx.tx.execute(st -> fx.orderRepository.findById(first.orderId()).orElseThrow().getTotalAmount());
        assertEquals(8000, restoredTotal, "되살린 주문 합계에서 자릿세가 빠진다");
    }

    @Test
    void restoringCanceledSeatFeeOrderKeepsFeeWhenNoOtherFeeCharged() {
        Long sessionId = fx.openSession();
        OrderCreateResponse first = order(sessionId);
        orderCancelService.cancel(first.orderId(), sessionId);

        dashboardOrderActionService.restore(bearer(fx.staffToken), first.orderId());

        assertEquals(1, chargedSeatFees(sessionId), "다른 곳에 자릿세가 없으면 되살린 주문의 자릿세가 그대로 청구된다");
    }

    @Test
    void restoringPaidThenCanceledOrderNeverChangesItsAmount() {
        Long sessionId = fx.openSession();
        OrderCreateResponse first = order(sessionId);
        String staff = bearer(fx.staffToken);
        dashboardOrderActionService.confirmPayment(staff, first.orderId(), PaymentMethod.BANK_TRANSFER);   // 돈을 받았다
        dashboardOrderActionService.cancelByStaff(staff, first.orderId(), "실수로 취소");                    // REFUND_NEEDED
        order(sessionId);   // 다음 주문에 자릿세가 다시 붙었다

        dashboardOrderActionService.restore(staff, first.orderId());

        int restoredTotal = fx.tx.execute(st -> fx.orderRepository.findById(first.orderId()).orElseThrow().getTotalAmount());
        assertEquals(8000 + 3000 * PARTY_SIZE, restoredTotal, "이미 받은 돈이 걸린 주문의 금액은 되돌리기로 바뀌면 안 된다");
    }
}
