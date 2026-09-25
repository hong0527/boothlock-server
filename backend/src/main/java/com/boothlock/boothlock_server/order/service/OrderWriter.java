package com.boothlock.boothlock_server.order.service;


import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 채번과 저장만 담당하는 쓰기 경계 — 별도 빈으로 둔 이유가 있다.
 * 멱등키 동시 요청은 unique 위반으로 이 트랜잭션이 롤백되는데, 같은 트랜잭션 안에서는
 * 재조회조차 할 수 없다(flush 실패 후 auto-flush가 다시 터지고 rollback-only가 걸린다).
 * 호출자가 트랜잭션 밖에서 예외를 받아 새 트랜잭션으로 복구하도록 경계를 여기서 끊는다.
 */
@Component
public class OrderWriter {

    /** 자릿세(명세서 밖, 파일럿 전용) — 1인당 금액. 인원수를 곱해 하나의 항목으로 붙인다 */
    public static final int SEAT_FEE_PER_PERSON = 3000;

    private final OrderRepository orderRepository;
    private  final OrderNumberingService numberingService;

    public OrderWriter(OrderRepository repository, OrderNumberingService numberingService)
    {
        this.orderRepository = repository;
        this.numberingService = numberingService;
    }


    /** 채번(MANDATORY)이 이 트랜잭션에 합류한다 — 저장 실패 시 번호도 함께 롤백된다. 종료된 세션이면 410 */
    @Transactional
    public OrderEntity save(OrderSpec spec)
    {
        // 세션 확인을 채번보다 먼저 — 종료된 세션이면 번호를 소모하지 않고, 잠금 순서도 세션→카운터로 고정된다.
        // 테이블 미지정 수기 주문(O14)은 세션이 없어 건너뛴다
        if (spec.sessionId() != null
                && orderRepository.touchIfSessionActive(spec.sessionId(), spec.createdAt()) == 0) {
            throw new SessionExpiredException();
        }
        // 자릿세는 세션 행을 잠근 뒤에 판정한다 — 같은 세션의 다른 주문 저장은 위 조건부 UPDATE에서 줄을 서므로,
        // 앞 주문이 커밋된 뒤에 여기를 지나 그 주문의 자릿세를 본다(READ COMMITTED). 판정을 잠금 밖에서 하면 이중 부과된다
        List<OrderItemEntity> items = new ArrayList<>(spec.items());
        int totalAmount = spec.totalAmount();
        Integer partySize = spec.seatFeePartySize();
        if (partySize != null && partySize > 0 && spec.sessionId() != null
                && !orderRepository.existsChargedSeatFee(spec.sessionId())) {
            items.add(OrderItemEntity.seatFee(SEAT_FEE_PER_PERSON, partySize));
            totalAmount += SEAT_FEE_PER_PERSON * partySize;
        }
        LocalDate businessDate = numberingService.businessDateOf(spec.createdAt());
        int orderSeq = numberingService.nextSeq(spec.boothId(), businessDate);
        OrderEntity order = new OrderEntity(
                spec.boothId(), spec.sessionId(), spec.label() + "-" + orderSeq, businessDate,
                orderSeq, spec.idempotencyKey(), totalAmount, spec.manual(),
                spec.tableLabel(), spec.createdAt());
        // O28 승인대기(v0.6.10) — 손님 주문(C3)만 승인 전까지 대기시킨다. 수기 주문(O14, spec.manual()=true)은
        // 운영자가 직접 입력한 것이라 스스로 승인한 것과 같아 기본값 RECEIVED를 그대로 둔다
        if (!spec.manual()) {
            order.startPendingApproval();
        }
        items.forEach(order::addItem);
        return orderRepository.saveAndFlush(order);

    }


    /**
     * 저장에 필요한 값 묶음 — label은 정규화본(orderNo용), tableLabel은 원본 스냅샷(O10 표시용). manual: O14 수기 주문이면 true.
     * seatFeePartySize: 손님 주문(C3)의 세션 인원수 — 이 세션에 청구된 자릿세가 없으면 save가 자릿세 항목을 붙인다. null이면 안 붙인다
     */
    public record OrderSpec(Long boothId, Long sessionId, String label, String tableLabel,
                            String idempotencyKey, int totalAmount, List<OrderItemEntity> items,
                            LocalDateTime createdAt, boolean manual, Integer seatFeePartySize) {

        /** 자릿세와 무관한 주문(수기 주문 O14 등) */
        public OrderSpec(Long boothId, Long sessionId, String label, String tableLabel,
                         String idempotencyKey, int totalAmount, List<OrderItemEntity> items,
                         LocalDateTime createdAt, boolean manual) {
            this(boothId, sessionId, label, tableLabel, idempotencyKey, totalAmount, items, createdAt, manual, null);
        }
    }

}
