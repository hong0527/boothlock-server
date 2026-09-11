package com.boothlock.boothlock_server.booth.repository;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoothRepository extends JpaRepository<BoothEntity, Long> {

    @Query(value = "select count(*) from booth_table where booth_id = :boothId", nativeQuery = true)
    long countTablesByBoothId(@Param("boothId") Long boothId);
}
