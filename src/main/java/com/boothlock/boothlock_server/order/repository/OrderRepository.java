package com.boothlock.boothlock_server.order.repository;

import com.boothlock.boothlock_server.order.domain.OrderEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    /** C3 멱등키 재요청 판정 — 같은 키면 새 주문 대신 기존 주문 200 (명세서 §6) */
    Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);
}
