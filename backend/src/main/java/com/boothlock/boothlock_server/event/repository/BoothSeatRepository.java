package com.boothlock.boothlock_server.event.repository;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.global.domain.UnpaidOrderRule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 홈 화면(E1)의 부스 목록·좌석 집계 — 조회 전용.
 * 부스 파트·테이블 파트 리포지토리를 고치지 않으려고 이 패키지에 따로 둔다
 * (JPA는 같은 엔티티에 리포지토리를 여러 개 둘 수 있다).
 */
public interface BoothSeatRepository extends JpaRepository<BoothEntity, Long> {

    /**
     * 부스 메타와 좌석 현황을 <b>쿼리 한 번</b>에 가져온다. 부스 수와 무관하게 SELECT 1회다 —
     * 홈 화면은 폴링되므로 부스마다 조회를 돌면 부하가 부스 수만큼 늘어난다 (API 명세 §E1·§7-19).
     *
     * <p>빈자리 판정을 테이블 status가 아니라 <b>세션</b>으로 하는 이유가 중요하다.
     * status를 EMPTY로 되돌리는 경로는 퇴실(O6)뿐이고, 세션 유휴 만료는 스케줄러 없이 C1 재스캔 시점에만 기록된다.
     * 운영자가 퇴실을 누르지 않으면 status는 사용중으로 남으므로, status로 집계하면 행사 몇 시간 뒤 모든 부스가 만석으로 굳는다.
     * 세션 기준으로 보면 퇴실은 즉시, 퇴실 누락은 유휴 임계가 지나는 것만으로 빈자리가 된다.
     *
     * <p>활성 세션을 LEFT JOIN으로 붙이고 {@code s.id is null}인 테이블을 빈자리로 센다.
     * 테이블당 활성 세션은 최대 1개라(DB 유니크 제약) 조인으로 행이 늘지 않지만,
     * {@code count(distinct t.id)}로 한 번 더 방어한다.
     * <p>활성 세션 조건은 SeatIdlePolicy의 정의를 그대로 옮긴 것이다 — 최근 활동이 있거나, 현재 영업일의 미결제
     * ({@link UnpaidOrderRule}: RECEIVED·DONE && UNPAID) 주문이 있으면 활성 (명세 §7-9 미결제 세션은 영업일 종료로만 만료).
     * C1 복원·O3와 같은 정의이므로 한쪽만 바꾸지 말 것 — 미결제 조건은 UnpaidOrderRule.JPQL_CONDITION을 그대로 잇는다.
     * 미결제 확인은 ON 절의 EXISTS라 쿼리는 여전히 1회다. {@code o.boothId}·{@code o.businessDate}는 orders 인덱스
     * (booth_id, business_date, order_no) 앞부분을 태우려고 둔다(orders에는 session_id 인덱스가 없다).
     * {@code s.endedAtKey = 0}은 uq_session_active(table_id, ended_at_key)를 끝까지 태우려고 둔다 — ended_at IS NULL만으로는
     * 종료된 과거 세션을 전부 읽는다(MySQL 8.4 실측 세션 5만 건 27ms → 1ms, 결과 동일).
     * {@code t.id is not null} 조건은 테이블이 없는 부스가 LEFT JOIN 때문에 빈자리 1로 세어지는 것을 막는다.
     * {@code t.active = true}는 삭제(soft delete)된 테이블을 좌석 수에서 뺀다 — O3·O16과 같은 기준이다.
     */
    @Query("""
            select b.id as boothId, b.name as name, b.category as category, b.open as open,
                   b.mapX as mapX, b.mapY as mapY,
                   count(distinct t.id) as totalTables,
                   coalesce(sum(case when t.id is not null and s.id is null then 1 else 0 end), 0) as emptyTables
              from BoothEntity b
              left join com.boothlock.boothlock_server.tableqr.domain.TableEntity t on t.booth = b and t.active = true
              left join com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity s
                     on s.table = t
                    and s.endedAtKey = 0
                    and s.endedAt is null
                    and (s.lastActivityAt > :idleSince
                         or exists (select o.id
                                      from com.boothlock.boothlock_server.order.domain.OrderEntity o
                                     where o.boothId = b.id
                                       and o.businessDate = :businessDate
                                       and o.sessionId = s.id
                                       and """ + UnpaidOrderRule.JPQL_CONDITION + """
            ))
             group by b.id, b.name, b.category, b.open, b.mapX, b.mapY
             order by b.id asc
            """)
    List<BoothSeatRow> findSeatSummaries(@Param("idleSince") LocalDateTime idleSince,
                                         @Param("businessDate") LocalDate businessDate);
}
