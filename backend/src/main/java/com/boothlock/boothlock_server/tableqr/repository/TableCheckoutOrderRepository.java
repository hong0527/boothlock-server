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
}
