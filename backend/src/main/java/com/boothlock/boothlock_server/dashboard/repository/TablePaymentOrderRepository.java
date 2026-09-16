package com.boothlock.boothlock_server.dashboard.repository;

import com.boothlock.boothlock_server.global.domain.UnpaidOrderRule;
import com.boothlock.boothlock_server.order.domain.OrderEntity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * O24 테이블 일괄 입금 확인의 대상 조회 — 주문 엔티티는 주문 파트 소유라 OrderRepository를 고치지 않고
 * 대시보드 파트에 조회 전용 리포지토리를 따로 둔다 (CRUD 메서드가 딸려 오지 않게 Repository만 상속).
 */
public interface TablePaymentOrderRepository extends Repository<OrderEntity, Long> {

    /**
     * 그 테이블의 종료 안 된 세션(ended_at IS NULL and ended_at_key = 0)에 붙은 미결제({@link UnpaidOrderRule}: RECEIVED·DONE && UNPAID)
     * 주문 전부를 id 순으로 잠근다. 범위는 O3 좌석 현황 unpaidOrderCount·O6 퇴실 경고와 같은 상태 조건이다 —
     * 완료 처리된 미입금 주문도 여기서 함께 확인되어야 '결제 완료' 뒤에 미수금이 남지 않는다. C3 rate limit(RECEIVED만)과는 다르다.
     * id 순 잠금은 두 일괄 결제가 겹칠 때 서로 반대 순서로 잠가 교착되는 것을 막는다.
     * 서브쿼리의 세션 행은 잠기지 않는다(MySQL 8: 바깥 FOR UPDATE는 서브쿼리에 적용되지 않음) — 퇴실(O6)·C1과 락 순서가 얽히지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from OrderEntity o
            where o.boothId = :boothId
              and """ + UnpaidOrderRule.JPQL_CONDITION + """

              and o.sessionId in (
                    select s.id from TableSessionEntity s
                    where s.table.id = :tableId and s.table.booth.id = :boothId
                      and s.endedAt is null and s.endedAtKey = 0)
            order by o.id asc
            """)
    List<OrderEntity> findUnpaidOfActiveTableSessionsForUpdate(@Param("boothId") Long boothId,
                                                               @Param("tableId") Long tableId);
}
