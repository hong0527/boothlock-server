package com.boothlock.boothlock_server.order.repository;

import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * C5 소비자 취소용 행 잠금 — 조회와 상태 변경 사이에 입금 확인(O11)이 커밋되면 "취소됐는데 PAID"가 된다.
     * FOR UPDATE로 주문 행을 잠그면 O11의 조건부 UPDATE가 이 트랜잭션 뒤로 줄 서고, 먼저 커밋된 O11은 여기서 PAID로 보인다.
     * items는 같은 트랜잭션에서 지연 로딩한다 — 조인 FETCH와 FOR UPDATE를 섞지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.id = :orderId and o.sessionId = :sessionId")
    Optional<OrderEntity> findByIdAndSessionIdForUpdate(@Param("orderId") Long orderId, @Param("sessionId") Long sessionId);

    /**
     * 결제 모달 항목 수량 변경·개별 취소용 행 잠금 — 상태 확인→항목 변경→합계 재계산 사이에 입금 확인(O11)·손님 취소(C5)·
     * 다른 항목 취소가 끼어들지 못하게 한다. 같은 주문의 두 항목을 동시에 취소해도 여기서 직렬화된다.
     * 단일 테이블 조회라 Hibernate가 {@code for update of orders}를 그대로 emit한다 — 격리수준과 무관하게 주문 행의 최신 커밋을 잠그고 읽는다.
     * 항목은 이 문장으로 읽지 않는다 — 잠금을 얻은 뒤 {@link #lockItemsByOrderId}로 따로 잠금 읽기한다 (그쪽 주석 참조)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.id = :orderId and o.boothId = :boothId")
    Optional<OrderEntity> findByIdAndBoothIdForUpdate(@Param("orderId") Long orderId, @Param("boothId") Long boothId);

    /**
     * 주문 항목을 잠금 읽기(FOR UPDATE)로 최신 커밋까지 읽어 영속성 컨텍스트에 올린다 — 이후 {@code order.getItems()}가 이 최신 사본을 쓴다.
     * <p>왜 join fetch가 아니라 이 네이티브 쿼리인가: 컬렉션 join fetch에 {@code @Lock}을 걸어도 Hibernate는 바깥 조인 컬렉션에는
     * FOR UPDATE를 붙이지 않고 떼어 낸다(실측: {@code left join order_item ... order by id}만 emit, {@code for update} 없음).
     * 그러면 그 항목 읽기가 잠금 없는 조회가 되어 MySQL REPEATABLE READ에서 트랜잭션 첫 SELECT(인증) 시점 스냅샷의 옛 항목이 나오고,
     * 상대가 방금 취소한 항목을 살아 있는 것으로 본다(MySQL 8.4 실측: 두 항목 동시 취소가 RECEIVED/RECEIVED로 남음).
     * 단일 테이블에 {@code for update}를 직접 적은 네이티브 쿼리는 Hibernate가 손대지 않아 실제 잠금 읽기가 되고, 최신 커밋을 본다(H2·MySQL 공통).
     * 주문 행 잠금({@link #findByIdAndBoothIdForUpdate})을 먼저 잡은 뒤 부르므로, 이 항목들의 유일한 수정자(같은 주문의 편집)와도 직렬화돼 교착이 없다.
     * 반환값은 쓰지 않는다 — 영속성 컨텍스트에 최신 항목을 올리는 것이 목적이다
     */
    @Query(value = "select * from order_item where order_id = :orderId for update", nativeQuery = true)
    List<OrderItemEntity> lockItemsByOrderId(@Param("orderId") Long orderId);

    /**
     * 주문 저장 직전 세션 생존 확인 — 종료 안 된 세션이면 활동 시각을 갱신하고 1, 이미 종료됐으면 0 (명세서 C3 1단계).
     * 인증(TableSessionAuthService)과 저장이 다른 트랜잭션이라 그 사이 퇴실(O6)이 커밋되면 주문이 종료된 세션에 붙는다.
     * 반드시 주문 INSERT와 같은 트랜잭션에서 부른다 — 이 UPDATE가 세션 행 잠금을 커밋까지 쥐고 있어 동시 퇴실이 이 주문 뒤로 줄 선다.
     * 세션 엔티티는 테이블 파트 소유라 파일을 건드리지 않고 주문 쪽에서 JPQL로만 접근한다. 테이블 행은 잠그지 않는다(교착 방지).
     */
    @Modifying
    @Query("""
            update TableSessionEntity s
               set s.lastActivityAt = :now
             where s.id = :sessionId
               and s.endedAt is null
               and s.endedAtKey = 0
            """)
    int touchIfSessionActive(@Param("sessionId") Long sessionId, @Param("now") LocalDateTime now);

    /** C3 미결제 상한 — 세션당 RECEIVED+UNPAID 8건 초과 시 429 (명세서 C3 4단계) */
    long countBySessionIdAndStatusAndPaymentStatus(Long sessionId, OrderStatus status, PaymentStatus paymentStatus);

    /** C4 내 주문 조회 — 최신순 (동시각 대비 id 보조 정렬). EntityGraph: 폴링 N+1 방지 — items를 조인으로 한 번에 */
    @EntityGraph(attributePaths = "items")
    List<OrderEntity> findBySessionIdOrderByCreatedAtDescIdDesc(Long sessionId);

    /**
     * O10 대시보드 조회 — status/paymentStatus/businessDate/q/tableId는 전부 선택(null이면 조건 무시) (명세서 O10)
     * limit: 탭(진행/완료/취소)별로 화면엔 최신 것만 보여주고 그 이전 건 검색(q)으로 찾게 함 — 무한히 쌓이는 것 방지 (MVP: 30건 고정)
     * tableId 필터: OrderEntity.sessionId는 연관 매핑이 아니라 raw Long이라 o.session.table.id 같은 경로 탐색이
     * 컴파일되지 않는다 — TableSessionEntity를 서브쿼리로 이어 찾는다 (명세서 O10 "구현 주의")
     * activeSessionOnly: tableId와 함께 true면 그 테이블의 종료 안 된 세션(ended_at IS NULL and ended_at_key = 0) 주문만 —
     * 결제 모달이 "지금 앉은 손님" 주문만 보는 수단이다(O24 대상 범위와 같은 세션 조건). tableId 없이 true인 요청은 서비스가 400으로 막는다.
     * ended_at_key = 0을 함께 거는 이유는 TableSessionRepository 주석(uq_session_active 인덱스)과 같다.
     */
    @EntityGraph(attributePaths = "items")
    @Query("""
            select o from OrderEntity o
            where o.boothId = :boothId
              and (:status is null or o.status = :status)
              and (:paymentStatus is null or o.paymentStatus = :paymentStatus)
              and (:businessDate is null or o.businessDate = :businessDate)
              and (:q is null or o.orderNo like concat('%', :q, '%'))
              and (:tableId is null or o.sessionId in (
                  select s.id from TableSessionEntity s
                  where s.table.id = :tableId and s.table.booth.id = :boothId
                    and (:activeSessionOnly = false or (s.endedAt is null and s.endedAtKey = 0))))
            order by o.createdAt desc, o.id desc
            """)
    List<OrderEntity> searchForDashboard(
            @Param("boothId") Long boothId,
            @Param("status") OrderStatus status,
            @Param("paymentStatus") PaymentStatus paymentStatus,
            @Param("businessDate") LocalDate businessDate,
            @Param("q") String q,
            @Param("tableId") Long tableId,
            @Param("activeSessionOnly") boolean activeSessionOnly,
            Limit limit);

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

    /**
     * O13 운영자 취소 — 소비자 취소(C5)와 달리 DONE도 취소할 수 있다 (명세서 O13).
     * 입금된 주문이면 결제 축을 REFUND_NEEDED로 함께 넘긴다 — CASE로 같은 문장 안에서 처리해야
     * 동시 입금확인과 겹쳐도 환불 대상이 누락되지 않는다 (O11·O12와 같은 레이스 이유).
     * reason 길이(1~100자) 검증은 호출자 몫 — 여기서 안 거르면 저장 단계 500이 난다 (cancel_reason VARCHAR(100))
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update OrderEntity o
               set o.status = com.boothlock.boothlock_server.global.domain.OrderStatus.CANCELED,
                   o.cancelReason = :reason,
                   o.canceledBy = :canceledBy,
                   o.canceledAt = :canceledAt,
                   o.paymentStatus = case when o.paymentStatus = com.boothlock.boothlock_server.global.domain.PaymentStatus.PAID
                                          then com.boothlock.boothlock_server.global.domain.PaymentStatus.REFUND_NEEDED
                                          else o.paymentStatus end
             where o.id = :orderId
               and o.boothId = :boothId
               and o.status <> com.boothlock.boothlock_server.global.domain.OrderStatus.CANCELED
            """)
    int cancelByStaff(
            @Param("orderId") Long orderId,
            @Param("boothId") Long boothId,
            @Param("reason") String reason,
            @Param("canceledBy") String canceledBy,
            @Param("canceledAt") LocalDateTime canceledAt);

    /**
     * O21 환불 완료 — REFUND_NEEDED만 허용해 송금 없이 기록만 정리하는 경로를 막는다 (허위 환불 방지, 명세서 O21).
     * ADMIN 전용 권한 검사는 호출자(컨트롤러) 몫 — 여기는 상태 전이만 지킨다
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update OrderEntity o
               set o.paymentStatus = com.boothlock.boothlock_server.global.domain.PaymentStatus.REFUNDED,
                   o.refundedBy = :refundedBy,
                   o.refundedAt = :refundedAt
             where o.id = :orderId
               and o.boothId = :boothId
               and o.paymentStatus = com.boothlock.boothlock_server.global.domain.PaymentStatus.REFUND_NEEDED
            """)
    int markRefunded(
            @Param("orderId") Long orderId,
            @Param("boothId") Long boothId,
            @Param("refundedBy") String refundedBy,
            @Param("refundedAt") LocalDateTime refundedAt);
}
