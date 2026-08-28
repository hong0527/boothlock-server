package com.boothlock.boothlock_server.order.repository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    /** C3 멱등키 재요청 판정 — 같은 키면 새 주문 대신 기존 주문 200 (명세서 §6) */
    @EntityGraph(attributePaths = "items")
    Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = "items")
    Optional<OrderEntity> findByIdAndSessionId(Long id, Long sessionId);

    long countBySessionIdAndStatusAndPaymentStatus(Long sessionId, OrderStatus status, PaymentStatus paymentStatus);


    /** C4 내 주문 조회 — 최신순 (동시각 대비 id 보조 정렬). EntityGraph: 폴링 N+1 방지 — items를 조인으로 한 번에 */
    @EntityGraph(attributePaths = "items")
    List<OrderEntity> findBySessionIdOrderByCreatedAtDescIdDesc(Long sessionId);
}
