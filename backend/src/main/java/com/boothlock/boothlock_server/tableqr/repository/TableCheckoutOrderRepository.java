package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.order.domain.OrderEntity;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * O6 퇴실 때 그 손님의 남은 접수 주문을 완료로 넘기는 쓰기 — 주문 파트 리포지토리를 고치지 않으려고 이 패키지에 따로 둔다
 * (TableSequenceRepository와 같은 방식). 쓰기 메서드가 더 생기지 않도록 빈 Repository를 확장한다.
 *
 * <p>O12 완료(OrderRepository.markDone)와 같은 조건부 UPDATE라 RECEIVED만 DONE이 된다 — 취소·완료된 주문은 그대로고,
 * 입금 상태(paymentStatus)는 건드리지 않는다(2축 상태). flushAutomatically: 호출자가 먼저 바꾼 테이블 status를 이 UPDATE 전에 내보낸다.
 * clear는 하지 않는다 — 호출자가 응답을 만들 때 쓰는 테이블 엔티티가 영속성 컨텍스트에서 떨어지면 안 된다.
 * 호출자는 반드시 테이블 행을 잠그고 세션을 종료한 트랜잭션 안에서 부른다(TableAdminService.checkoutTable).
 */
public interface TableCheckoutOrderRepository extends Repository<OrderEntity, Long> {

    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderEntity o
               set o.status = com.boothlock.boothlock_server.global.domain.OrderStatus.DONE
             where o.boothId = :boothId
               and o.sessionId in :sessionIds
               and o.status = com.boothlock.boothlock_server.global.domain.OrderStatus.RECEIVED
            """)
    int completeReceivedOrdersOfSessions(@Param("sessionIds") List<Long> sessionIds, @Param("boothId") Long boothId);
}
