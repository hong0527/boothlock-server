package com.boothlock.boothlock_server;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaffAccountRepository extends JpaRepository<StaffAccountEntity, Long> {

    Optional<StaffAccountEntity> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);
}
