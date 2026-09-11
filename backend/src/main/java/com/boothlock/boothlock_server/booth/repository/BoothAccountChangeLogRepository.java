package com.boothlock.boothlock_server.booth.repository;

import com.boothlock.boothlock_server.booth.domain.BoothAccountChangeLogEntity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BoothAccountChangeLogRepository extends JpaRepository<BoothAccountChangeLogEntity, Long> {
}
