package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.order.domain.OrderEntity;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * O6 퇴실 때 그 손님의 남은 접수 주문을 완료로 넘기는 쓰기 — 주문 파트 리포지토리를 고치지 않으려고 이 패키지에 따로 둔다
 * (TableSequenceRepository와 같은 방식). 쓰기 메서드가 더 생기지 않도록 빈 Repository를 확장한다.
 *
 * <p>자동 완료(completeReceivedOrdersOfSessions)는 O12 완료(OrderRepository.markDone)와 같은 조건부 UPDATE라 RECEIVED만 DONE이 된다 —
 * 취소·완료된 주문은 그대로고, 입금 상태(paymentStatus)는 건드리지 않는다(2축 상태). 자동 거절은 O13처럼 PAID만 REFUND_NEEDED로 넘긴다(아래). flushAutomatically: 호출자가 먼저 바꾼 테이블 status를 이 UPDATE 전에 내보낸다.
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

    /**
     * O6 퇴실 때 그 손님의 남은 승인대기(O28) 주문을 자동 거절한다(v0.6.10) — 승인대기는 UnpaidOrderRule에서
     * 일부러 뺐기 때문에(주문 도메인 주석 참조) 위 completeReceivedOrdersOfSessions처럼 자동 완료 대상이 아니고,
     * requireSettled 판정에도 안 잡힌다. 그대로 두면 테이블이 비워진 뒤에도 "승인 대기" 탭에 남의 손님 없는
     * 주문이 영영 남는다(손님 세션은 이미 종료돼 토큰도 410이라 본인은 알 방법이 없다) — O13(cancelByStaff)과
     * 같은 조건부 UPDATE를 여기서도 쓴다(별도 엔티티 메서드 없이 markDone류 패턴을 따른다)
     *
     * <p>입금 축도 O13과 똑같이 PAID → REFUND_NEEDED로 함께 넘긴다 — O11 입금 확인(markPaid)은 CANCELED만 막으므로
     * 승인대기 주문도 PAID가 될 수 있다(#117 승인대기 카드의 "결제 확인" 버튼). 여기서 주문 축만 CANCELED로 바꾸면
     * CANCELED+PAID가 남아 환불 목록(REFUND_NEEDED)에는 안 뜨고 매출(PAID)에는 그대로 잡힌다 — 받은 돈을 돌려줄 길이 사라진다.
     * CASE를 같은 문장에 둬야 동시 입금 확인과 겹쳐도 환불 대상이 빠지지 않는다(cancelByStaff 주석과 같은 이유).
     * 호출자: O6 퇴실(TableAdminService.checkoutTable)과 C1 유휴 재스캔의 옛 세션 종료(TableSessionWriter.createSession)
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderEntity o
               set o.status = com.boothlock.boothlock_server.global.domain.OrderStatus.CANCELED,
                   o.cancelReason = :reason,
                   o.canceledBy = :canceledBy,
                   o.canceledAt = :canceledAt,
                   o.paymentStatus = case when o.paymentStatus = com.boothlock.boothlock_server.global.domain.PaymentStatus.PAID
                                          then com.boothlock.boothlock_server.global.domain.PaymentStatus.REFUND_NEEDED
                                          else o.paymentStatus end
             where o.boothId = :boothId
               and o.sessionId in :sessionIds
               and o.status = com.boothlock.boothlock_server.global.domain.OrderStatus.PENDING_APPROVAL
            """)
    int rejectPendingApprovalOrdersOfSessions(
            @Param("sessionIds") List<Long> sessionIds,
            @Param("boothId") Long boothId,
            @Param("reason") String reason,
            @Param("canceledBy") String canceledBy,
            @Param("canceledAt") LocalDateTime canceledAt);

    /**
     * O6 퇴실 때 면제할 자릿세 주문 — 종료하는 세션에 살아 있는 메뉴 주문이 하나도 없으면(주문했다가 "안 먹겠다"로 거절·취소)
     * 그 세션의 미입금 자릿세 전용 주문(살아 있는 자릿세 항목만 남은 DONE+UNPAID)을 고른다. 음식을 하나라도 먹었으면 대상이 아니다.
     * 자릿세는 조리할 게 없어 처음부터 DONE이라 자동 거절(승인대기)·자동 완료(접수) 어느 쪽에도 안 걸려, 비운 뒤에도
     * 완료 탭에 "자릿세·미결제"로 영영 남았다. 입금된 자릿세는 건드리지 않는다(돌려줄지는 운영자가 O13으로 정한다).
     * 고르기와 취소를 나눈다 — MySQL은 UPDATE 대상 테이블을 같은 문장의 서브쿼리에서 읽지 못한다(1093)
     */
    @Query("""
            select o.id from OrderEntity o
             where o.boothId = :boothId
               and o.sessionId in :sessionIds
               and o.status = com.boothlock.boothlock_server.global.domain.OrderStatus.DONE
               and o.paymentStatus = com.boothlock.boothlock_server.global.domain.PaymentStatus.UNPAID
               and exists (select 1 from OrderEntity f join f.items fi
                            where f = o and fi.canceled = false
                              and fi.itemType = com.boothlock.boothlock_server.order.domain.OrderItemType.SEAT_FEE)
               and not exists (select 1 from OrderEntity x join x.items xi
                                where x = o and xi.canceled = false
                                  and xi.itemType <> com.boothlock.boothlock_server.order.domain.OrderItemType.SEAT_FEE)
               and not exists (select 1 from OrderEntity m join m.items mi
                                where m.sessionId = o.sessionId and m.boothId = o.boothId
                                  and m.status <> com.boothlock.boothlock_server.global.domain.OrderStatus.CANCELED
                                  and mi.canceled = false
                                  and mi.itemType = com.boothlock.boothlock_server.order.domain.OrderItemType.MENU)
            """)
    List<Long> findWaivableSeatFeeOrderIdsOfSessions(@Param("sessionIds") List<Long> sessionIds, @Param("boothId") Long boothId);

    /** 위에서 고른 자릿세 주문을 면제(CANCELED)한다 — 조건을 다시 걸어 그 사이 입금 확인된 건은 건드리지 않는다 */
    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderEntity o
               set o.status = com.boothlock.boothlock_server.global.domain.OrderStatus.CANCELED,
                   o.cancelReason = :reason,
                   o.canceledBy = :canceledBy,
                   o.canceledAt = :canceledAt
             where o.id in :orderIds
               and o.status = com.boothlock.boothlock_server.global.domain.OrderStatus.DONE
               and o.paymentStatus = com.boothlock.boothlock_server.global.domain.PaymentStatus.UNPAID
            """)
    int waiveSeatFeeOrders(
            @Param("orderIds") List<Long> orderIds,
            @Param("reason") String reason,
            @Param("canceledBy") String canceledBy,
            @Param("canceledAt") LocalDateTime canceledAt);

    /**
     * O26 자리 합석(명세서 밖 파일럿) — source 세션의 주문을 전부 target 세션 밑으로 옮긴다. 각 주문의
     * status·paymentStatus는 그대로 둔다 — 합석은 "누구 자리에서 났나"만 바꾸고 결제·환불 여부는 건드리지
     * 않는다. orderNo(테이블 접두 채번)도 그대로다 — 이미 낸 번호를 다시 매기지 않는다(주문 이력 불변 원칙).
     * 호출자(TableAdminService.mergeSessions)가 두 세션 행을 잠근 트랜잭션 안에서 부른다
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderEntity o
               set o.sessionId = :targetSessionId
             where o.boothId = :boothId
               and o.sessionId = :sourceSessionId
            """)
    int reassignOrdersToSession(@Param("sourceSessionId") Long sourceSessionId,
                                 @Param("targetSessionId") Long targetSessionId,
                                 @Param("boothId") Long boothId);
}
