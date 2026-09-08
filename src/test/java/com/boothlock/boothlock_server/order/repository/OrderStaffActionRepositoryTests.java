package com.boothlock.boothlock_server.order.repository;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * O13 운영자 취소·O21 환불 완료 조건부 UPDATE 검증 (명세서 O13·O21·§2 상태머신).
 * 쿼리가 자체 @Transactional로 즉시 커밋하므로 테스트 롤백을 쓰지 않고 직접 정리한다.
 */
@SpringBootTest
class OrderStaffActionRepositoryTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 18, 34);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private BoothRepository boothRepository;

    private Long boothId;

    @BeforeEach
    void setUp() {
        boothId = boothRepository.save(
                new BoothEntity("테스트 부스", "카카오뱅크 3333-01-1234567 (홍길동)", "18:00~02:00")
        ).getId();
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        boothRepository.deleteById(boothId);
    }

    private OrderEntity newOrder(int seq) {
        return orderRepository.save(new OrderEntity(
                boothId, 10L, "A3-" + seq, LocalDate.of(2026, 9, 15),
                seq, "idem-" + seq, 21000, false, NOW.minusMinutes(4)));
    }

    private OrderEntity reload(Long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    // ── O13 운영자 취소 ──────────────────────────────────

    @Test
    void cancelRecordsReasonAndActor() {
        Long id = newOrder(1).getId();

        int updated = orderRepository.cancelByStaff(id, boothId, "재료 소진", "staff_5", NOW);

        assertEquals(1, updated);
        OrderEntity order = reload(id);
        assertEquals(OrderStatus.CANCELED, order.getStatus());
        assertEquals("재료 소진", order.getCancelReason());
        assertEquals("staff_5", order.getCanceledBy());     // 소비자 취소의 "CUSTOMER"와 구분
        assertEquals(NOW, order.getCanceledAt());
    }

    @Test
    void cancelingPaidOrderMovesToRefundNeeded() {
        // 두 축이 교차하는 지점 — 입금된 주문의 취소는 환불 대상으로 남아야 한다 (명세서 §2)
        Long id = newOrder(1).getId();
        orderRepository.markPaid(id, boothId, PaymentMethod.BANK_TRANSFER, "staff_5", NOW);

        orderRepository.cancelByStaff(id, boothId, "재료 소진", "staff_5", NOW);

        assertEquals(PaymentStatus.REFUND_NEEDED, reload(id).getPaymentStatus());
    }

    @Test
    void cancelingUnpaidOrderKeepsUnpaid() {
        Long id = newOrder(1).getId();

        orderRepository.cancelByStaff(id, boothId, "손님 요청", "staff_5", NOW);

        assertEquals(PaymentStatus.UNPAID, reload(id).getPaymentStatus());   // 받은 돈이 없으니 환불도 없다
    }

    @Test
    void staffCanCancelDoneOrder() {
        // 소비자(C5)는 RECEIVED만 취소 가능하지만 운영자는 전달 완료분도 취소한다 (명세서 O13)
        Long id = newOrder(1).getId();
        orderRepository.markDone(id, boothId);

        assertEquals(1, orderRepository.cancelByStaff(id, boothId, "음식 문제", "staff_5", NOW));
        assertEquals(OrderStatus.CANCELED, reload(id).getStatus());
    }

    @Test
    void cancelTwiceUpdatesNothing() {
        Long id = newOrder(1).getId();
        orderRepository.cancelByStaff(id, boothId, "손님 요청", "staff_5", NOW);

        int second = orderRepository.cancelByStaff(id, boothId, "중복 시도", "staff_9", NOW.plusMinutes(1));

        assertEquals(0, second);                                   // 호출자가 0을 보고 409로 바꾼다
        assertEquals("손님 요청", reload(id).getCancelReason());   // 원래 기록이 안 덮인다
    }

    @Test
    void cancelIsScopedToBooth() {
        // 타 부스 주문은 0건 — 호출자가 404로 바꾼다 (존재 은닉, 명세서 §1.4)
        Long id = newOrder(1).getId();

        assertEquals(0, orderRepository.cancelByStaff(id, boothId + 999, "남의 부스", "staff_5", NOW));
        assertEquals(OrderStatus.RECEIVED, reload(id).getStatus());
    }

    // ── O21 환불 완료 ────────────────────────────────────

    @Test
    void refundDoneRecordsHandler() {
        Long id = newOrder(1).getId();
        orderRepository.markPaid(id, boothId, PaymentMethod.BANK_TRANSFER, "staff_5", NOW);
        orderRepository.cancelByStaff(id, boothId, "재료 소진", "staff_5", NOW);

        int updated = orderRepository.markRefunded(id, boothId, "admin_1", NOW.plusMinutes(10));

        assertEquals(1, updated);
        OrderEntity order = reload(id);
        assertEquals(PaymentStatus.REFUNDED, order.getPaymentStatus());
        assertEquals("admin_1", order.getRefundedBy());
        assertEquals(NOW.plusMinutes(10), order.getRefundedAt());
    }

    @Test
    void refundRequiresRefundNeededState() {
        // 송금 없이 기록만 정리하는 경로 차단 (허위 환불 방지, 명세서 O21)
        Long unpaid = newOrder(1).getId();
        assertEquals(0, orderRepository.markRefunded(unpaid, boothId, "admin_1", NOW));

        Long paid = newOrder(2).getId();
        orderRepository.markPaid(paid, boothId, PaymentMethod.CASH, "staff_5", NOW);
        assertEquals(0, orderRepository.markRefunded(paid, boothId, "admin_1", NOW));
        assertNull(reload(paid).getRefundedBy());
    }

    @Test
    void refundTwiceUpdatesNothing() {
        Long id = newOrder(1).getId();
        orderRepository.markPaid(id, boothId, PaymentMethod.BANK_TRANSFER, "staff_5", NOW);
        orderRepository.cancelByStaff(id, boothId, "재료 소진", "staff_5", NOW);
        orderRepository.markRefunded(id, boothId, "admin_1", NOW);

        assertEquals(0, orderRepository.markRefunded(id, boothId, "admin_2", NOW.plusMinutes(5)));
        assertEquals("admin_1", reload(id).getRefundedBy());   // 첫 처리 기록이 안 덮인다
    }
}
