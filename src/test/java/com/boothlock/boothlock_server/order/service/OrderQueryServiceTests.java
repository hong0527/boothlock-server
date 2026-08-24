package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.UnauthorizedException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.dto.OrderListResponse;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class OrderQueryServiceTests {

    @Autowired
    private OrderQueryService orderQueryService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private BoothRepository boothRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    private Long boothId;

    @BeforeEach
    void setUp() {
        boothId = boothRepository.save(
                new BoothEntity("테스트 부스", "카카오뱅크 3333-01-1234567 (홍길동)", "18:00~02:00")
        ).getId();
    }

    private OrderEntity newOrder(Long sessionId, int orderSeq, LocalDateTime createdAt) {
        OrderEntity order = new OrderEntity(
                boothId,
                sessionId,
                "A3-" + orderSeq,
                LocalDate.of(2026, 8, 22),
                orderSeq,
                "idem-" + orderSeq,
                16000,
                sessionId == null,
                createdAt
        );
        order.addItem(new OrderItemEntity(3L, "김치전", 8000, 2));
        return orderRepository.save(order);
    }

    @Test
    void returnsOrdersNewestFirstWithItemsAndPayment() {
        newOrder(1L, 1, LocalDateTime.of(2026, 8, 22, 18, 0));
        OrderEntity second = newOrder(1L, 2, LocalDateTime.of(2026, 8, 22, 19, 30));
        second.addItem(new OrderItemEntity(5L, "제로콜라", 5000, 1)); // 다건 항목 — @OrderBy 검증용

        entityManager.flush();
        entityManager.clear();

        OrderListResponse response = orderQueryService.getOrders(1L);

        assertEquals(2, response.orders().size());
        OrderListResponse.OrderSummary latest = response.orders().get(0);
        assertEquals("A3-2", latest.orderNo());                       // 최신순 — 19:30이 먼저
        assertEquals(OrderStatus.RECEIVED, latest.status());
        assertEquals(PaymentStatus.UNPAID, latest.paymentStatus());
        assertEquals(16000, latest.totalAmount());
        assertEquals(2, latest.items().size());
        assertEquals("김치전", latest.items().get(0).menuName());     // 먼저 담은 항목이 먼저 (@OrderBy id)
        assertEquals(8000, latest.items().get(0).unitPrice());
        assertEquals("제로콜라", latest.items().get(1).menuName());
        assertEquals("카카오뱅크 3333-01-1234567 (홍길동)", latest.payment().bankAccount());
        assertTrue(latest.payment().depositorNameRule().contains("A3-2")); // 주문번호가 안내문에 조립됨
        assertEquals(OffsetDateTime.of(2026, 8, 22, 19, 30, 0, 0, ZoneOffset.ofHours(9)),
                latest.createdAt());                                  // 시각 보존 + +09:00 표기
        assertEquals("A3-1", response.orders().get(1).orderNo());
    }

    @Test
    void breaksCreatedAtTiesByIdDescending() {
        LocalDateTime sameMoment = LocalDateTime.of(2026, 8, 22, 18, 0);
        newOrder(1L, 1, sameMoment);
        newOrder(1L, 2, sameMoment);          // 같은 시각 — 나중에 저장돼 id가 더 큼

        entityManager.flush();
        entityManager.clear();

        List<OrderListResponse.OrderSummary> orders = orderQueryService.getOrders(1L).orders();
        assertEquals("A3-2", orders.get(0).orderNo());   // 동시각이면 id 큰(나중) 주문이 먼저
    }

    @Test
    void computesCanCancelOnlyForReceivedUnpaid() {
        Long cancelable = newOrder(1L, 1, LocalDateTime.of(2026, 8, 22, 18, 0)).getId();
        Long paid = newOrder(1L, 2, LocalDateTime.of(2026, 8, 22, 18, 10)).getId();
        Long canceled = newOrder(1L, 3, LocalDateTime.of(2026, 8, 22, 18, 20)).getId();

        entityManager.flush();
        // 상태 전이 도메인 메서드는 4강에서 생긴다 — 그 전까지는 테스트가 벌크 UPDATE로 상태를 만든다
        entityManager.createQuery("update OrderEntity o set o.paymentStatus = :ps where o.id = :id")
                .setParameter("ps", PaymentStatus.PAID).setParameter("id", paid).executeUpdate();
        entityManager.createQuery("update OrderEntity o set o.status = :st where o.id = :id")
                .setParameter("st", OrderStatus.CANCELED).setParameter("id", canceled).executeUpdate();
        entityManager.clear();

        OrderListResponse response = orderQueryService.getOrders(1L);

        assertEquals(3, response.orders().size());                    // 취소된 주문도 포함 (명세서 C4)
        for (OrderListResponse.OrderSummary summary : response.orders()) {
            if (summary.orderId().equals(cancelable)) {
                assertTrue(summary.canCancel());                      // RECEIVED+UNPAID만 true
            } else {
                assertFalse(summary.canCancel());                     // PAID·CANCELED는 false
            }
            if (summary.orderId().equals(paid)) {
                assertEquals(PaymentStatus.PAID, summary.paymentStatus());  // 상태가 그대로 실려 나가는지
            }
            if (summary.orderId().equals(canceled)) {
                assertEquals(OrderStatus.CANCELED, summary.status());
            }
        }
    }

    @Test
    void serializesToSpecShapedJson() throws Exception {
        newOrder(1L, 17, LocalDateTime.of(2026, 8, 22, 18, 30));

        entityManager.flush();
        entityManager.clear();

        String json = objectMapper.writeValueAsString(orderQueryService.getOrders(1L));

        // 컨트롤러 연결(6강) 전까지 명세서 C4의 JSON 키·형식을 테스트로 고정해 둔다
        assertTrue(json.contains("\"orders\":["));
        assertTrue(json.contains("\"orderNo\":\"A3-17\""));
        assertTrue(json.contains("\"status\":\"RECEIVED\""));
        assertTrue(json.contains("\"paymentStatus\":\"UNPAID\""));
        assertTrue(json.contains("\"menuName\":\"김치전\""));
        assertTrue(json.contains("\"canCancel\":true"));
        assertTrue(json.contains("\"createdAt\":\"2026-08-22T18:30:00+09:00\""));
    }

    @Test
    void excludesOtherSessionsAndManualOrders() {
        newOrder(1L, 1, LocalDateTime.of(2026, 8, 22, 18, 0));
        newOrder(2L, 2, LocalDateTime.of(2026, 8, 22, 18, 5));        // 다른 테이블 세션
        newOrder(null, 3, LocalDateTime.of(2026, 8, 22, 18, 10));     // 수기 주문 (세션 없음)

        entityManager.flush();
        entityManager.clear();

        List<OrderListResponse.OrderSummary> orders = orderQueryService.getOrders(1L).orders();

        assertEquals(1, orders.size());                               // 내 세션 것만
        assertEquals("A3-1", orders.get(0).orderNo());
    }

    @Test
    void returnsEmptyListForSessionWithoutOrders() {
        assertEquals(0, orderQueryService.getOrders(99L).orders().size());
    }

    @Test
    void rejectsNullSessionId() {
        // null이면 파생 쿼리가 IS NULL이 되어 수기 주문이 노출된다 — 401로 차단
        assertThrows(UnauthorizedException.class, () -> orderQueryService.getOrders(null));
    }
}
