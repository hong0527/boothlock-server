package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TableSessionRepository extends JpaRepository<TableSessionEntity, Long> {

    Optional<TableSessionEntity> findBySessionToken(String sessionToken);

    Optional<TableSessionEntity> findByTableIdAndEndedAtIsNull(Long tableId);
}
