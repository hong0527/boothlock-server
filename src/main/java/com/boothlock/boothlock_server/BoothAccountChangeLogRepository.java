package com.boothlock.boothlock_server;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BoothAccountChangeLogRepository extends JpaRepository<BoothAccountChangeLogEntity, Long> {
}
