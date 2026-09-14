package com.boothlock.boothlock_server.event.repository;

import com.boothlock.boothlock_server.event.domain.EventMapEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EventMapRepository extends JpaRepository<EventMapEntity, Long> {

    /**
     * 가장 최근 약도 1건 — id 기준이다.
     * updatedAt은 시딩으로 넣는 값이라 과거 시각이 들어갈 수 있어 정렬 기준으로 쓰지 않는다 (DB스키마 v1.3).
     */
    Optional<EventMapEntity> findFirstByOrderByIdDesc();
}
