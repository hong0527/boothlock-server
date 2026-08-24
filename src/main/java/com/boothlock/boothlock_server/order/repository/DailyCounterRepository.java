package com.boothlock.boothlock_server.order.repository;

import com.boothlock.boothlock_server.order.domain.DailyCounterEntity;
import com.boothlock.boothlock_server.order.domain.DailyCounterId;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyCounterRepository extends JpaRepository<DailyCounterEntity, DailyCounterId> {
}
