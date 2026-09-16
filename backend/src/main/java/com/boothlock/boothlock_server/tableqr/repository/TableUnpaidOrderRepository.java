package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.global.domain.UnpaidOrderRule;
import com.boothlock.boothlock_server.order.domain.OrderEntity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 테이블별 미결제 주문 건수 — 조회 전용 (명세서 O3 unpaidOrderCount·O6 warning).
 * 주문 파트 리포지토리를 고치지 않으려고 이 패키지에 따로 둔다(JPA는 같은 엔티티에 리포지토리를 여러 개 둘 수 있다).
 * 쓰기 메서드가 생기지 않도록 JpaRepository가 아니라 빈 Repository를 확장한다 (O6의 FOR UPDATE 조회도 읽기다 — 주문 행은 고치지 않는다).
 */
public interface TableUnpaidOrderRepository extends Repository<OrderEntity, Long> {

    /**
     * 테이블 목록의 미결제 건수를 <b>쿼리 한 번</b>에 센다 — 테이블마다 세면 O3 폴링 부하가 테이블 수만큼 는다.
     *
     * <p>세는 조건은 {@link UnpaidOrderRule}(RECEIVED·DONE && UNPAID)다 — 완료 처리됐어도 입금이 안 됐으면 미수금이라 센다.
     * 취소된 미결제(CANCELED+UNPAID)와 입금 완료는 빠진다. C3 미결제 상한(RECEIVED만)과는 다른 조건이다.
     * 범위는 그 테이블의 <b>종료되지 않은 세션</b>이다 — 유휴 여부와 무관하다. 손님이 3시간 넘게 조용했어도
     * 퇴실 전에 확인해야 할 미결제는 그대로 남아 있기 때문이다. 퇴실로 종료된 과거 세션의 미결제는 세지 않는다
     * (다음 손님 자리에 앞 손님 미수금이 붙어 보이면 안 된다 — 과거 건은 대시보드 O10에서 본다).
     *
     * <p>{@code unpaidOrderCountToday}는 그중 {@code businessDate} 영업일 주문 수다 — SeatIdlePolicy의 "미결제 보유 세션은
     * 영업일 종료로만 만료" 판정 재료라 같은 쿼리에서 함께 센다.
     *
     * <p>주문의 sessionId는 연관관계가 아닌 원시 Long이라 세션과 세타 조인으로 잇는다. 수기 주문(sessionId NULL)은 조인에서 자연히 빠진다.
     * {@code o.boothId} 조건은 결과를 바꾸지 않지만 orders의 인덱스(booth_id, business_date, order_no) 앞부분을 태우려고 둔다 —
     * orders에는 session_id 인덱스가 없다. 열린 세션 조건에는 ended_at_key = 0을 함께 건다(TableSessionRepository 주석).
     * 미결제가 0건인 테이블은 결과 행이 없다 — 호출자가 0으로 채운다.
     */
    @Query("""
            select s.table.id as tableId,
                   count(o.id) as unpaidOrderCount,
                   sum(case when o.businessDate = :businessDate then 1 else 0 end) as unpaidOrderCountToday
              from OrderEntity o, com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity s
             where o.sessionId = s.id
               and o.boothId = s.table.booth.id
               and s.table.id in :tableIds
               and s.endedAtKey = 0
               and s.endedAt is null
               and """ + UnpaidOrderRule.JPQL_CONDITION + """

             group by s.table.id
            """)
    List<TableUnpaidCountRow> countUnpaidOrdersOfOpenSessions(@Param("tableIds") List<Long> tableIds,
                                                              @Param("businessDate") LocalDate businessDate);

    /**
     * O6 퇴실 경고 — 방금 종료한 세션들의 미결제({@link UnpaidOrderRule}) 주문. 건수는 호출자가 센다.
     * 세션 행 잠금 조회·종료 UPDATE가 주문 저장 트랜잭션의 세션 행 잠금을 기다린 뒤에 읽으므로(TableAdminService.checkoutTable)
     * 종료 직전에 끼어든 주문까지 빠짐없이 잡힌다. 그 주문을 보려면 반드시 잠금 읽기(FOR UPDATE)여야 한다 —
     * count 일반 조회는 MySQL REPEATABLE READ에서 트랜잭션 첫 SELECT(인증) 시점 스냅샷을 세서 그 주문을 빠뜨린다(MySQL 8.4 실측).
     * count(...) FOR UPDATE는 H2가 거부하므로(집계에 FOR UPDATE 불가) 행을 읽어 센다 — 한 테이블의 미결제는 몇 건 수준이다.
     * 열린 세션 조건을 걸지 않는다 — 이미 종료된 세션의 주문을 읽는 쿼리다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o
              from OrderEntity o
             where o.boothId = :boothId
               and o.sessionId in :sessionIds
               and """ + UnpaidOrderRule.JPQL_CONDITION)
    List<OrderEntity> findUnpaidOrdersOfSessionsForUpdate(@Param("sessionIds") List<Long> sessionIds, @Param("boothId") Long boothId);

    /** C1 세션 복원 판정 — 이 세션에 해당 영업일의 미결제({@link UnpaidOrderRule}) 주문이 있는가 (SeatIdlePolicy 활성 조건 2) */
    @Query("""
            select count(o.id) > 0
              from OrderEntity o
             where o.boothId = :boothId
               and o.businessDate = :businessDate
               and o.sessionId = :sessionId
               and """ + UnpaidOrderRule.JPQL_CONDITION)
    boolean existsUnpaidOrderOn(@Param("sessionId") Long sessionId,
                                @Param("boothId") Long boothId,
                                @Param("businessDate") LocalDate businessDate);
}
