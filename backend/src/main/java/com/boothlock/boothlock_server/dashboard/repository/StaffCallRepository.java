package com.boothlock.boothlock_server.dashboard.repository;

import com.boothlock.boothlock_server.dashboard.domain.CallReason;
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

    /**
     * C6 30초 재호출 제한 — 같은 세션·같은 사유의 가장 최근 호출 1건 (v0.6.11부터 사유별 독립).
     * PAYMENT를 일반 호출(HELP·WATER·ETC)과 분리하려고 사유를 조건에 넣었다 — 그 결과 일반 호출끼리도
     * 서로 독립된 쿨다운을 갖게 됐다(예: HELP 직후 WATER 호출도 막히지 않는다). 명세서 C6도 이 규칙으로 갱신했다
     */
    Optional<StaffCallEntity> findFirstBySession_IdAndReasonOrderByCreatedAtDesc(Long sessionId, CallReason reason);
}
