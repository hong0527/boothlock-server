package com.boothlock.boothlock_server.order.domain;

import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;

import com.boothlock.boothlock_server.global.error.InvalidStateException;
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
        indexes = @Index(
                name = "idx_orders_search",
                columnList = "booth_id, business_date, order_no"))
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<OrderItemEntity> getItems() {
        return Collections.unmodifiableList(items);
    }
}
