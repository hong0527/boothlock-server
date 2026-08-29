package com.boothlock.boothlock_server.order.service;


import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 채번과 저장만 담당하는 쓰기 경계 — 별도 빈으로 둔 이유가 있다.
 * 멱등키 동시 요청은 unique 위반으로 이 트랜잭션이 롤백되는데, 같은 트랜잭션 안에서는
 * 재조회조차 할 수 없다(flush 실패 후 auto-flush가 다시 터지고 rollback-only가 걸린다).
 * 호출자가 트랜잭션 밖에서 예외를 받아 새 트랜잭션으로 복구하도록 경계를 여기서 끊는다.
 */
@Component
public class OrderWriter {

    private final OrderRepository orderRepository;
    private  final OrderNumberingService numberingService;

    public OrderWriter(OrderRepository repository, OrderNumberingService numberingService)
    {
        this.orderRepository = repository;
        this.numberingService = numberingService;
    }


    /** 채번(MANDATORY)이 이 트랜잭션에 합류한다 — 저장 실패 시 번호도 함께 롤백된다 */
    @Transactional
    public OrderEntity save(OrderSpec spec)
    {
        LocalDate businessDate = numberingService.businessDateOf(spec.createdAt());
        int orderSeq = numberingService.nextSeq(spec.boothId(), businessDate);
        OrderEntity order = new OrderEntity(
                spec.boothId(), spec.sessionId(), spec.label() + "-" + orderSeq, businessDate,
                orderSeq, spec.idempotencyKey(), spec.totalAmount(), false, spec.createdAt());
        spec.items().forEach(order::addItem);
        return orderRepository.saveAndFlush(order);

    }


    /** 저장에 필요한 값 묶음 — 파라미터가 길어져 record로 모았다 */
    public record OrderSpec(Long boothId, Long sessionId, String label, String idempotencyKey,
                            int totalAmount, List<OrderItemEntity> items,
                            LocalDateTime createdAt) {
    }

}
