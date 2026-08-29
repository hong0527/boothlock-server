package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.error.UnauthorizedException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.dto.OrderListResponse;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class OrderCancelServiceTests {

    private static final Long MY_SESSION = 1L;

    @Autowired
    private OrderCancelService orderCancelService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private BoothRepository boothRepository;

    @Autowired
    private EntityManager entityManager;

    private Long boothId;

    @BeforeEach
    void setUp() {
        boothId = boothRepository.save(
                new BoothEntity("테스트 부스", "카카오뱅크 3333-01-1234567 (홍길동)", "18:00~02:00")
        ).getId();
    }

    private OrderEntity newOrder(Long sessionId, int orderSeq) {
        OrderEntity order = new OrderEntity(
                boothId, sessionId, "A3-" + orderSeq, LocalDate.of(2026, 8, 22),
                orderSeq, "idem-" + orderSeq, 16000, sessionId == null,
                LocalDateTime.of(2026, 8, 22, 18, 0));
        order.addItem(new OrderItemEntity(3L, "김치전", 8000, 2));
        return orderRepository.save(order);
    }

    @Test
    void cancelsReceivedUnpaidOrderAndReturnsSummary() {
        Long orderId = newOrder(MY_SESSION, 1).getId();

        entityManager.flush();
        entityManager.clear();

        OrderListResponse.OrderSummary summary = orderCancelService.cancel(orderId, MY_SESSION);

        assertEquals(OrderStatus.CANCELED, summary.status());
        assertEquals(PaymentStatus.UNPAID, summary.paymentStatus());   // 결제 축은 그대로 (2축 상태)
        assertFalse(summary.canCancel());                              // 취소된 주문은 재취소 불가 표시
        assertEquals("A3-1", summary.orderNo());
        assertEquals(1, summary.items().size());                       // C4 단건 형태 — 항목까지 포함
    }

    @Test
    void recordsWhoAndWhenOnCancel() {
        Long orderId = newOrder(MY_SESSION, 1).getId();

        entityManager.flush();
        entityManager.clear();

        orderCancelService.cancel(orderId, MY_SESSION);

        entityManager.flush();
        entityManager.clear();

        OrderEntity saved = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CANCELED, saved.getStatus());         // dirty checking으로 저장됐나
        assertEquals("CUSTOMER", saved.getCanceledBy());               // 누가 (분쟁 대비)
        assertNotNull(saved.getCanceledAt());                          // 언제
    }

    @Test
    void rejectsPaidOrderWithInvalidState() {
        Long orderId = newOrder(MY_SESSION, 1).getId();

        entityManager.flush();
        entityManager.createQuery("update OrderEntity o set o.paymentStatus = :ps where o.id = :id")
                .setParameter("ps", PaymentStatus.PAID).setParameter("id", orderId).executeUpdate();
        entityManager.clear();

        // 입금 확인된 주문은 손님이 못 취소한다 — 409 INVALID_STATE (명세서 C5)
        assertThrows(InvalidStateException.class, () -> orderCancelService.cancel(orderId, MY_SESSION));
    }

    @Test
    void rejectsDoneOrderWithInvalidState() {
        Long orderId = newOrder(MY_SESSION, 1).getId();

        entityManager.flush();
        entityManager.createQuery("update OrderEntity o set o.status = :st where o.id = :id")
                .setParameter("st", OrderStatus.DONE).setParameter("id", orderId).executeUpdate();
        entityManager.clear();

        // 완료된 주문도 손님이 못 취소한다 — 명세서 C5의 409 대상 3종(PAID·완료·취소) 중 하나
        assertThrows(InvalidStateException.class, () -> orderCancelService.cancel(orderId, MY_SESSION));
    }

    @Test
    void rejectsAlreadyCanceledOrder() {
        Long orderId = newOrder(MY_SESSION, 1).getId();

        entityManager.flush();
        entityManager.clear();

        orderCancelService.cancel(orderId, MY_SESSION);

        entityManager.flush();
        entityManager.clear();

        assertThrows(InvalidStateException.class, () -> orderCancelService.cancel(orderId, MY_SESSION));
    }

    @Test
    void hidesOtherSessionOrderWithNotFound() {
        Long otherOrderId = newOrder(2L, 1).getId();     // 옆 테이블 주문

        entityManager.flush();
        entityManager.clear();

        // 권한 오류가 아니라 404 — 남의 주문이 "존재한다"는 사실 자체를 숨긴다 (명세서 §1.4)
        assertThrows(NotFoundException.class, () -> orderCancelService.cancel(otherOrderId, MY_SESSION));
    }

    @Test
    void doesNotCancelOtherSessionOrder() {
        Long otherOrderId = newOrder(2L, 1).getId();

        entityManager.flush();
        entityManager.clear();

        assertThrows(NotFoundException.class, () -> orderCancelService.cancel(otherOrderId, MY_SESSION));

        entityManager.clear();
        OrderEntity untouched = orderRepository.findById(otherOrderId).orElseThrow();
        assertEquals(OrderStatus.RECEIVED, untouched.getStatus());     // 404 던지고 실제로 손도 안 댔나
        assertNull(untouched.getCanceledBy());                         // 취소 기록도 남지 않아야 한다
        assertNull(untouched.getCanceledAt());
    }

    @Test
    void rejectsUnknownOrderIdWithNotFound() {
        assertThrows(NotFoundException.class, () -> orderCancelService.cancel(9999L, MY_SESSION));
    }

    @Test
    void rejectsNullSessionId() {
        Long orderId = newOrder(MY_SESSION, 1).getId();

        entityManager.flush();
        entityManager.clear();

        assertThrows(UnauthorizedException.class, () -> orderCancelService.cancel(orderId, null));
    }

    @Test
    void keepsOtherOrdersUntouched() {
        Long target = newOrder(MY_SESSION, 1).getId();
        Long sibling = newOrder(MY_SESSION, 2).getId();

        entityManager.flush();
        entityManager.clear();

        orderCancelService.cancel(target, MY_SESSION);

        entityManager.flush();
        entityManager.clear();

        List<OrderEntity> orders = orderRepository.findBySessionIdOrderByCreatedAtDescIdDesc(MY_SESSION);
        assertEquals(2, orders.size());
        assertEquals(OrderStatus.RECEIVED,
                orders.stream().filter(o -> o.getId().equals(sibling)).findFirst().orElseThrow().getStatus());
    }
}
