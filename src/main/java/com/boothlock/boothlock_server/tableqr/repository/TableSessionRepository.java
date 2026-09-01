package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TableSessionRepository extends JpaRepository<TableSessionEntity, Long> {

    Optional<TableSessionEntity> findBySessionToken(String sessionToken);

    Optional<TableSessionEntity> findByTableIdAndEndedAtIsNull(Long tableId);

    /** C6 동시 호출 직렬화 — 세션 row에 배타 락을 걸어 같은 세션의 동시 요청을 줄 세운다 (김재원 추가, 전형준 리뷰 필요) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TableSessionEntity s where s.id = :id")
    Optional<TableSessionEntity> findByIdForUpdate(@Param("id") Long id);
}
