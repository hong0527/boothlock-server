package com.boothlock.boothlock_server.booth.repository;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BoothRepository extends JpaRepository<BoothEntity, Long> {
}
