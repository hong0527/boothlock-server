package com.boothlock.boothlock_server.order.repository;

import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.DailyCounterEntity;
import com.boothlock.boothlock_server.order.domain.DailyCounterId;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class OrderDomainRepositoryTests {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private DailyCounterRepository dailyCounterRepository;

    @Autowired
    private EntityManager entityManager;

    private OrderEntity newOrder(Long boothId, LocalDate businessDate, int orderSeq, String idempotencyKey) {
        return new OrderEntity(
                boothId,
                null,
                "A3-" + orderSeq,
                businessDate,
                orderSeq,
                idempotencyKey,
                13000,
                idempotencyKey == null,
                LocalDateTime.of(2026, 8, 22, 18, 30)
        );
    }

    @Test
    void savesOrderWithItemSnapshotsAndDefaults() {
        OrderEntity order = newOrder(1L, LocalDate.of(2026, 8, 22), 17, "idem-key-001");
        order.addItem(new OrderItemEntity(10L, "떡볶이", 5000, 2));
        order.addItem(new OrderItemEntity(11L, "콜라", 3000, 1));
        orderRepository.save(order);

        entityManager.flush();
        entityManager.clear();

        OrderEntity saved = orderRepository.findById(order.getId()).orElseThrow();
        assertNotNull(saved.getId());
        assertEquals("A3-17", saved.getOrderNo());
        assertEquals(OrderStatus.RECEIVED, saved.getStatus());
        assertEquals(PaymentStatus.UNPAID, saved.getPaymentStatus());
        assertNull(saved.getPaymentMethod());
        assertEquals(13000, saved.getTotalAmount());
        assertEquals(2, saved.getItems().size());
        OrderItemEntity item = saved.getItems().get(0);
        assertEquals("떡볶이", item.getMenuName());
        assertEquals(5000, item.getUnitPrice());
        assertEquals(10000, item.subtotal());
        assertNull(saved.getSessionId());
        assertFalse(saved.isManual());
        // 멱등키로도 같은 주문이 찾아져야 C3 재요청=기존 주문 200이 성립한다
        assertEquals(saved.getId(), orderRepository.findByIdempotencyKey("idem-key-001").orElseThrow().getId());
    }

    @Test
    void rejectsDuplicateNumberingWithinSameBusinessDate() {
        orderRepository.saveAndFlush(newOrder(1L, LocalDate.of(2026, 8, 22), 17, "idem-key-a"));

        // 같은 (booth_id, business_date, order_seq) — unique가 최후 방어선으로 거부해야 한다
        assertThrows(DataIntegrityViolationException.class, () ->
                orderRepository.saveAndFlush(newOrder(1L, LocalDate.of(2026, 8, 22), 17, "idem-key-b")));
    }

    @Test
    void allowsSameSeqOnDifferentBoothOrDate() {
        orderRepository.saveAndFlush(newOrder(1L, LocalDate.of(2026, 8, 22), 17, "idem-key-c"));
        orderRepository.saveAndFlush(newOrder(2L, LocalDate.of(2026, 8, 22), 17, "idem-key-d"));
        orderRepository.saveAndFlush(newOrder(1L, LocalDate.of(2026, 8, 23), 17, "idem-key-e"));

        assertEquals(3, orderRepository.count());
    }

    @Test
    void rejectsDuplicateIdempotencyKey() {
        orderRepository.saveAndFlush(newOrder(1L, LocalDate.of(2026, 8, 22), 1, "same-key"));

        // flush 실패 후에는 같은 영속성 컨텍스트를 재사용하지 않는다 — 실패한 INSERT가 다음 flush에서 재시도됨
        assertThrows(DataIntegrityViolationException.class, () ->
                orderRepository.saveAndFlush(newOrder(1L, LocalDate.of(2026, 8, 22), 2, "same-key")));
    }

    @Test
    void allowsMultipleNullIdempotencyKeys() {
        // 수기 주문(O14)은 멱등키 NULL — NULL끼리는 UNIQUE에 걸리지 않아야 여러 건 저장된다
        orderRepository.saveAndFlush(newOrder(1L, LocalDate.of(2026, 8, 22), 3, null));
        orderRepository.saveAndFlush(newOrder(1L, LocalDate.of(2026, 8, 22), 4, null));

        assertEquals(2, orderRepository.count());
    }

    @Test
    void findsCounterByCompositeKeyAndIncrements() {
        dailyCounterRepository.save(new DailyCounterEntity(1L, LocalDate.of(2026, 8, 22)));

        entityManager.flush();
        entityManager.clear();

        DailyCounterEntity counter = dailyCounterRepository
                .findById(new DailyCounterId(1L, LocalDate.of(2026, 8, 22)))
                .orElseThrow();
        assertEquals(0, counter.getLastSeq());
        assertEquals(1, counter.nextSeq());
        assertEquals(2, counter.nextSeq());

        entityManager.flush();
        entityManager.clear();

        DailyCounterEntity reloaded = dailyCounterRepository
                .findById(new DailyCounterId(1L, LocalDate.of(2026, 8, 22)))
                .orElseThrow();
        assertEquals(2, reloaded.getLastSeq());
    }
}
