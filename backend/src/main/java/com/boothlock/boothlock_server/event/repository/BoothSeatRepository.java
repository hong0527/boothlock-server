package com.boothlock.boothlock_server.event.repository;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 이 코드를 쓴 시점에 status를 OCCUPIED에서 EMPTY로 되돌리는 코드는 없다. 예정된 경로는 퇴실(O6)인데 아직 스텁이고,
     * 세션 유휴 자동 만료 로직도 코드에 없다(스케줄러·만료 판정 모두 부재 — 실측 확인).
     * status로 집계하면 손님이 QR을 찍는 순간 그 테이블이 영구히 사용중으로 남아
     * 행사 몇 시간 뒤 모든 부스가 만석으로 굳는다.
     * 세션 기준으로 보면 유휴 임계가 지나는 것만으로 자동으로 빈자리가 된다.
     * O6가 구현되면 퇴실 버튼이 세션을 종료시키므로 임계를 기다리지 않고 즉시 반영된다 —
     * 다만 O6는 아직 스텁이라 <b>오늘 동작하는 것은 유휴 임계 경로뿐이다.</b>
     *
     * <p>활성 세션을 LEFT JOIN으로 붙이고 {@code s.id is null}인 테이블을 빈자리로 센다.
     * 테이블당 활성 세션은 최대 1개라(DB 유니크 제약) 조인으로 행이 늘지 않지만,
     * {@code count(distinct t.id)}로 한 번 더 방어한다.
     * {@code t.id is not null} 조건은 테이블이 없는 부스가 LEFT JOIN 때문에 빈자리 1로 세어지는 것을 막는다.
     */
    @Query("""
            select b.id as boothId, b.name as name, b.category as category, b.open as open,
                   b.mapX as mapX, b.mapY as mapY,
                   count(distinct t.id) as totalTables,
                   coalesce(sum(case when t.id is not null and s.id is null then 1 else 0 end), 0) as emptyTables
              from BoothEntity b
              left join com.boothlock.boothlock_server.tableqr.domain.TableEntity t on t.booth = b
              left join com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity s
                     on s.table = t and s.endedAt is null and s.lastActivityAt > :idleSince
             group by b.id, b.name, b.category, b.open, b.mapX, b.mapY
             order by b.id asc
            """)
    List<BoothSeatRow> findSeatSummaries(@Param("idleSince") LocalDateTime idleSince);
}
