package com.boothlock.boothlock_server.order.repository;

import com.boothlock.boothlock_server.order.domain.OrderItemEntity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItemEntity, Long> {
}
