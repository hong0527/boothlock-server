package com.boothlock.boothlock_server.dashboard.repository;

import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StaffCallRepository extends JpaRepository<StaffCallEntity, Long> {

    /** O10 대시보드 — 부스 소속 미확인 호출만, 오래된 순 (명세서 O10) */
    @EntityGraph(attributePaths = {"session", "session.table"})
    @Query("""
            select c from StaffCallEntity c
            where c.session.table.booth.id = :boothId and c.acked = false
            order by c.createdAt asc
            """)
    List<StaffCallEntity> findUnackedByBoothId(@Param("boothId") Long boothId);

    /** O15 호출 확인 — 호출→세션→테이블→부스로 스코프해 타 부스 호출은 조회 단계에서 404가 되게 한다 (존재 은닉) */
    @Query("""
            select c from StaffCallEntity c
            where c.id = :callId and c.session.table.booth.id = :boothId
            """)
    Optional<StaffCallEntity> findByIdAndBoothId(@Param("callId") Long callId, @Param("boothId") Long boothId);

    /** C6 30초 재호출 제한 — 같은 세션의 가장 최근 호출 1건 (사유 무관) */
    Optional<StaffCallEntity> findFirstBySession_IdOrderByCreatedAtDesc(Long sessionId);
}
