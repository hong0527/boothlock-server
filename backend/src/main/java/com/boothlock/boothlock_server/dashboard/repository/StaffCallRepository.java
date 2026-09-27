package com.boothlock.boothlock_server.dashboard.repository;

import com.boothlock.boothlock_server.dashboard.domain.CallReason;
import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface StaffCallRepository extends JpaRepository<StaffCallEntity, Long> {

    /**
     * O10 대시보드 — 부스 소속 미확인 호출만, 오래된 순 (명세서 O10). 열린 세션의 호출만 — 퇴실(O6)·유휴 재스캔으로 끝난 세션의
     * 호출은 부를 손님이 이미 없다. 예전에는 acked=false만 봐서 퇴실 전에 확인을 못 누른 호출이 영업 내내 목록 맨 위에 남았다.
     * 열린 세션 조건은 TableSessionRepository와 같이 ended_at_key = 0과 ended_at IS NULL을 함께 건다
     */
    @EntityGraph(attributePaths = {"session", "session.table"})
    @Query("""
            select c from StaffCallEntity c
            where c.session.table.booth.id = :boothId and c.acked = false
              and c.session.endedAtKey = 0 and c.session.endedAt is null
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

    /**
     * O6 퇴실 — 종료하는 세션들의 미확인 호출을 확인 처리한다. 퇴실 트랜잭션 안에서 불러 "퇴실은 됐는데 호출은 남음"이 없게 한다.
     * 위 목록 조회도 열린 세션만 보지만, 여기서 acked까지 세워 두면 데이터 자체가 정리돼 다른 조회(통계 등)가 같은 함정에 빠지지 않는다
     */
    @Transactional
    @Modifying
    @Query("update StaffCallEntity c set c.acked = true where c.session.id in :sessionIds and c.acked = false")
    int ackUnackedCallsOfSessions(@Param("sessionIds") List<Long> sessionIds);
}
