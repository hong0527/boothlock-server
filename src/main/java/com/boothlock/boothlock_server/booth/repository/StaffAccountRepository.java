package com.boothlock.boothlock_server.booth.repository;

import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StaffAccountRepository extends JpaRepository<StaffAccountEntity, Long> {

    Optional<StaffAccountEntity> findByLoginId(String loginId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StaffAccountEntity s left join fetch s.booth where s.loginId = :loginId")
    Optional<StaffAccountEntity> findByLoginIdForUpdate(@Param("loginId") String loginId);

    boolean existsByLoginId(String loginId);
}
