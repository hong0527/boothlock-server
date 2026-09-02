package com.boothlock.boothlock_server.order.repository;

import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    /** C3 멱등키 재요청 판정 — 같은 키면 새 주문 대신 기존 주문 200 (명세서 §6). 응답 조립까지 하므로 items 동반 조회 */
    @EntityGraph(attributePaths = "items")
    Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);

    /** C5 소비자 취소 — 세션 조건을 쿼리에 넣어 남의 주문은 조회 단계에서 404가 되게 한다 (명세서 §1.4 존재 은닉) */
    @EntityGraph(attributePaths = "items")
    Optional<OrderEntity> findByIdAndSessionId(Long id, Long sessionId);

    /** C3 미결제 상한 — 세션당 RECEIVED+UNPAID 8건 초과 시 429 (명세서 C3 4단계) */
    long countBySessionIdAndStatusAndPaymentStatus(Long sessionId, OrderStatus status, PaymentStatus paymentStatus);

    /** C4 내 주문 조회 — 최신순 (동시각 대비 id 보조 정렬). EntityGraph: 폴링 N+1 방지 — items를 조인으로 한 번에 */
    @EntityGraph(attributePaths = "items")
    List<OrderEntity> findBySessionIdOrderByCreatedAtDescIdDesc(Long sessionId);

    /** O10 대시보드 조회 — status/paymentStatus/businessDate/q는 전부 선택(null이면 조건 무시) (명세서 O10) */
    @EntityGraph(attributePaths = "items")
    @Query("""
            select o from OrderEntity o
            where o.boothId = :boothId
              and (:status is null or o.status = :status)
              and (:paymentStatus is null or o.paymentStatus = :paymentStatus)
              and (:businessDate is null or o.businessDate = :businessDate)
              and (:q is null or o.orderNo like concat('%', :q, '%'))
            order by o.createdAt desc, o.id desc
            """)
    List<OrderEntity> searchForDashboard(
            @Param("boothId") Long boothId,
            @Param("status") OrderStatus status,
            @Param("paymentStatus") PaymentStatus paymentStatus,
            @Param("businessDate") LocalDate businessDate,
            @Param("q") String q);

    /** O11·O12 조회 — booth 범위로 스코프해 타 부스 주문은 조회 단계에서 404가 되게 한다 (존재 은닉) */
    @EntityGraph(attributePaths = "items")
    Optional<OrderEntity> findByIdAndBoothId(Long id, Long boothId);

    /**
     * O11 입금 확인 — 조건부 UPDATE(WHERE payment_status='UNPAID')로 상태 전이.
     * 조회 후 갱신으로 나누면 동시 클릭 시 두 요청 모두 조건을 통과해 승인 기록이 서로를 덮는다 (DB스키마 §3-9)
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update OrderEntity o
               set o.paymentStatus = com.boothlock.boothlock_server.global.domain.PaymentStatus.PAID,
                   o.paymentMethod = :method,
                   o.approvedBy = :approvedBy,
                   o.approvedAt = :approvedAt
             where o.id = :orderId
               and o.boothId = :boothId
               and o.paymentStatus = com.boothlock.boothlock_server.global.domain.PaymentStatus.UNPAID
               and o.status <> com.boothlock.boothlock_server.global.domain.OrderStatus.CANCELED
            """)
    int markPaid(
            @Param("orderId") Long orderId,
            @Param("boothId") Long boothId,
            @Param("method") PaymentMethod method,
            @Param("approvedBy") String approvedBy,
            @Param("approvedAt") LocalDateTime approvedAt);

    /** O12 완료 처리 — 조건부 UPDATE(WHERE status='RECEIVED'), 같은 레이스 이유로 O11과 동일 패턴 */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update OrderEntity o
               set o.status = com.boothlock.boothlock_server.global.domain.OrderStatus.DONE
             where o.id = :orderId
               and o.boothId = :boothId
               and o.status = com.boothlock.boothlock_server.global.domain.OrderStatus.RECEIVED
            """)
    int markDone(@Param("orderId") Long orderId, @Param("boothId") Long boothId);
}
