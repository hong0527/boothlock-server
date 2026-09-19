package com.boothlock.boothlock_server.order.domain;

import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;

import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 주문 (DB스키마 §1 orders — `ORDER`는 SQL 예약어라 테이블명 orders).
 * 4개 파트(주문·대시보드·테이블·정산)가 함께 쓰는 교차점 — 필드 추가는 CONTRIBUTING 0-3 절차로.
 */
@Entity
@DynamicUpdate   // 변경된 컬럼만 UPDATE — 전체 컬럼을 쓰면 동시 입금확인(O11)의 승인 기록을 덮는다 (DB스키마 §3-9)
@Table(
        name = "orders",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_orders_seq",
                columnNames = {"booth_id", "business_date", "order_seq"}),
        indexes = {
                @Index(name = "idx_orders_search", columnList = "booth_id, business_date, order_no"),
                // 세션 단위 조회용 — O6 퇴실 경고·O3 미결제 집계·O10 activeSessionOnly·C1 유휴 판정이 session_id로 찾는다.
                // 없으면 부스 인덱스 앞부분만 타고 그 부스의 주문을 훑는다 (운영 스키마 SQL의 idx_orders_session과 같은 이름)
                @Index(name = "idx_orders_session", columnList = "session_id")})
public class OrderEntity {

    /** 소비자 취소의 canceled_by 표기 — 운영자 취소는 운영자 loginId를 넣는다 (DB스키마 §1) */
    private static final String CANCELED_BY_CUSTOMER = "CUSTOMER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booth_id", nullable = false)
    private Long boothId;

    // 수기 주문(O14)은 테이블 미지정이라 NULL 허용 (DB스키마 §1)
    @Column(name = "session_id")
    private Long sessionId;

    // 주문 시점 테이블 라벨 원본 스냅샷(예 "A-3") — 세션이 ID 참조라 조인 경로가 없어 O10 표시·O19 CSV용으로 저장. 수기 주문은 NULL
    @Column(name = "table_label", length = 20)
    private String tableLabel;

    @Column(name = "order_no", nullable = false, length = 20)
    private String orderNo;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "order_seq", nullable = false)
    private int orderSeq;

    // NULL 허용 + UNIQUE 조합 그대로 유지할 것 — 수기 주문은 NULL, C3 더블탭은 UNIQUE가 차단 (DB스키마 §1)
    @Column(name = "idempotency_key", unique = true, length = 64)
    private String idempotencyKey;

    // JdbcTypeCode(VARCHAR): Hibernate 6 기본은 DB 네이티브 ENUM 타입 — 정본은 VARCHAR(20) (DB스키마 §1)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.RECEIVED;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Column(name = "cancel_reason", length = 100)
    private String cancelReason;

    @Column(name = "canceled_by", length = 50)
    private String canceledBy;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "approved_by", length = 50)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "refunded_by", length = 50)
    private String refundedBy;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "is_manual", nullable = false)
    private boolean manual = false;

    // 취소 주문 삭제(명세서 밖) — 실제 삭제가 아니라 숨김. 대시보드 목록(O10)에서만 제외되고 결제·정산 데이터는 그대로 남는다
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean hidden = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // 재조회 시 리스트 순서는 보장되지 않는다 — C4 응답 순서 고정용
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("id ASC")
    private List<OrderItemEntity> items = new ArrayList<>();

    protected OrderEntity() {
    }

    public OrderEntity(Long boothId, Long sessionId, String orderNo, LocalDate businessDate,
                       int orderSeq, String idempotencyKey, int totalAmount, boolean manual,
                       LocalDateTime createdAt) {
        this.boothId = boothId;
        this.sessionId = sessionId;
        this.orderNo = orderNo;
        this.businessDate = businessDate;
        this.orderSeq = orderSeq;
        this.idempotencyKey = idempotencyKey;
        this.totalAmount = totalAmount;
        this.manual = manual;
        this.createdAt = createdAt;
    }

    /** C3용 — 테이블 라벨 스냅샷까지 받는다. 기존 생성자는 라벨 없는 경로(수기 주문·타 파트 테스트)를 위해 유지 */
    public OrderEntity(Long boothId, Long sessionId, String orderNo, LocalDate businessDate,
                       int orderSeq, String idempotencyKey, int totalAmount, boolean manual,
                       String tableLabel, LocalDateTime createdAt) {
        this(boothId, sessionId, orderNo, businessDate, orderSeq, idempotencyKey, totalAmount, manual, createdAt);
        this.tableLabel = tableLabel;
    }

    public void addItem(OrderItemEntity item) {
        items.add(item);
    }

    /** 취소 가능 판정 — C4 응답의 canCancel과 C5 실행 조건이 같은 곳에서 나오게 한다 (명세서 C4·C5) */
    public boolean canCancel() {
        return status == OrderStatus.RECEIVED && paymentStatus == PaymentStatus.UNPAID;
    }

    public void cancelByCustomer(LocalDateTime canceledAt) {
        if (!canCancel()) {
            throw new InvalidStateException("취소할 수 없는 주문 상태입니다");
        }
        this.status = OrderStatus.CANCELED;              // 주문 축만 전이
        this.canceledBy = CANCELED_BY_CUSTOMER;          // 누가 (1강 감사 필드)
        this.canceledAt = canceledAt;
        // paymentStatus는 건드리지 않는다 — 미입금 취소는 환불 대상이 아님 (2축 상태)
    }

    /**
     * O6 결제 모달 항목 단위 수정 가능 판정 — 접수 후 입금 전까지만 허용 (canCancel과 동일 조건).
     * 호출자는 이 판정을 행 잠금(findByIdAndBoothIdForUpdate) 아래에서 읽어야 한다 — 잠금 없이 읽은 UNPAID는
     * 커밋 순서에 따라 이미 지난 상태일 수 있다 (audit2 B1)
     */
    public boolean canEditItems() {
        return status == OrderStatus.RECEIVED && paymentStatus == PaymentStatus.UNPAID;
    }

    /** O6 결제 모달 수량 +/- — 취소된 항목은 대상에서 제외(못 찾은 것과 동일하게 취급). 증가 시 품절·마감 검사는 호출자 몫 */
    public void updateItemQty(Long itemId, int qty) {
        if (!canEditItems()) {
            throw new InvalidStateException("항목을 수정할 수 없는 주문 상태입니다.");
        }
        requireEditableItem(itemId).updateQty(qty);
        recalculateTotal();
    }

    /**
     * O6 결제 모달 개별 "취소" — 항목을 숨기고(행은 남긴다) 합계를 다시 계산한다.
     * 남은 항목이 하나도 없으면 true를 돌려준다 — 주문 자체의 CANCELED 전이는 호출자가 O13과 같은 조건부 UPDATE
     * (OrderRepository.cancelByStaff)로 처리한다. 엔티티에서 status를 바꾸면 취소 기록 경로가 두 벌이 된다 (audit2 M6)
     */
    public boolean cancelItem(Long itemId) {
        if (!canEditItems()) {
            throw new InvalidStateException("항목을 수정할 수 없는 주문 상태입니다.");
        }
        requireEditableItem(itemId).cancel();
        recalculateTotal();
        return !hasRemainingItems();
    }

    /** 취소되지 않은 항목이 하나라도 남아 있나 */
    public boolean hasRemainingItems() {
        return items.stream().anyMatch(item -> !item.isCanceled());
    }

    /**
     * 취소복구(명세서 밖) — CANCELED만 RECEIVED로 되돌린다. paymentStatus·환불 관련 필드는 이 결제/환불과 무관한
     * 주문현황 관리 기능이라 건드리지 않는다(2축 상태, cancelByCustomer와 같은 원칙). 항목이 전부 개별
     * 취소(O6)돼 실질적으로 빈 주문이면 되살릴 대상이 없으므로 막는다.
     */
    public void restore() {
        if (hidden) {
            throw new InvalidStateException("삭제된 주문은 복구할 수 없습니다.");
        }
        if (status != OrderStatus.CANCELED) {
            throw new InvalidStateException("취소된 주문만 복구할 수 있습니다.");
        }
        if (!hasRemainingItems()) {
            throw new InvalidStateException("모든 항목이 취소된 주문은 복구할 수 없습니다.");
        }
        this.status = OrderStatus.RECEIVED;
    }

    /** 수정 대상 항목 — 이 주문의 것이 아니거나 이미 취소된 항목은 404 (남의 주문 항목 id를 끼워 넣어도 같은 응답) */
    public OrderItemEntity requireEditableItem(Long itemId) {
        return items.stream()
                .filter(item -> item.getId().equals(itemId) && !item.isCanceled())
                .findFirst()
                .orElseThrow(() -> new NotFoundException("주문 항목을 찾을 수 없습니다."));
    }

    /** subtotal은 취소 안 된 항목만 합산 — DB스키마 §3-3(파생값)과 같은 원칙을 취소 항목까지 확장 */
    private void recalculateTotal() {
        this.totalAmount = items.stream()
                .filter(item -> !item.isCanceled())
                .mapToInt(OrderItemEntity::subtotal)
                .sum();
    }

    public Long getId() {
        return id;
    }

    public Long getBoothId() {
        return boothId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getTableLabel() {
        return tableLabel;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public int getOrderSeq() {
        return orderSeq;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public int getTotalAmount() {
        return totalAmount;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public String getCanceledBy() {
        return canceledBy;
    }

    public LocalDateTime getCanceledAt() {
        return canceledAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public String getRefundedBy() {
        return refundedBy;
    }

    public LocalDateTime getRefundedAt() {
        return refundedAt;
    }

    public boolean isManual() {
        return manual;
    }

    public boolean isHidden() {
        return hidden;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<OrderItemEntity> getItems() {
        return Collections.unmodifiableList(items);
    }
}
