package com.boothlock.boothlock_server.order.service;


import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.SessionExpiredException;
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


    /**
     * 채번(MANDATORY)이 이 트랜잭션에 합류한다 — 저장 실패 시 번호도 함께 롤백된다. 종료된 세션이면 410.
     * 손님 주문(C3)이면 이 세션의 자릿세를 여기서 함께 정한다(spec.seatFee) — 아직 처리된 적 없으면 자릿세만 든 주문을
     * 메뉴 주문 바로 앞에 따로 만든다. 같은 트랜잭션이라 메뉴 주문 저장이 실패하면 자릿세 주문도 함께 사라진다
     */
    @Transactional
    public OrderEntity save(OrderSpec spec)
    {
        // 세션 확인을 채번보다 먼저 — 종료된 세션이면 번호를 소모하지 않고, 잠금 순서도 세션→카운터로 고정된다.
        // 테이블 미지정 수기 주문(O14)은 세션이 없어 건너뛴다
        if (spec.sessionId() != null
                && orderRepository.touchIfSessionActive(spec.sessionId(), spec.createdAt()) == 0) {
            throw new SessionExpiredException();
        }
        // 자리 이동(명세서 밖)은 세션 행을 잠그고 테이블을 바꾼다 — 호출자가 잠금 전에 읽은 라벨은 옛 자리일 수 있어, 잠금 뒤 현재 테이블로 다시 정한다.
        // 이동 중에 들어온 주문은 위 조건부 UPDATE에서 줄을 섰다가 이동이 커밋된 뒤 새 자리를 읽는다(READ COMMITTED)
        if (spec.sessionId() != null) {
            spec = spec.withTableLabel(orderRepository.findCurrentTableLabelOfSession(spec.sessionId()));
        }
        // 할인(기타 항목의 음수 금액)은 그 테이블의 미결제 합계까지만 — 받을 돈보다 많이 깎으면 결제확인·정산에 마이너스가 남는다.
        // 세션 행을 잠근 뒤에 합계를 본다: 같은 테이블의 다른 저장·결제확인과 엇갈려 한도를 두 번 쓰지 않게
        if (spec.totalAmount() < 0) {
            requireUnpaidCovers(spec.sessionId(), spec.totalAmount());
        }
        LocalDate businessDate = numberingService.businessDateOf(spec.createdAt());
        // 자릿세는 세션 행을 잠근 뒤에 판정한다 — 같은 세션의 다른 주문 저장은 위 조건부 UPDATE에서 줄을 서므로,
        // 앞 주문이 커밋된 뒤에 여기를 지나 그 자릿세를 본다(READ COMMITTED). 판정을 잠금 밖에서 하면 이중 부과된다
        if (spec.seatFee() != null && spec.sessionId() != null && !isSeatFeeHandled(spec.sessionId(), spec.createdAt())) {
            saveSeatFeeOrder(spec, businessDate);
        }
        int orderSeq = numberingService.nextSeq(spec.boothId(), businessDate);
        OrderEntity order = new OrderEntity(
                spec.boothId(), spec.sessionId(), spec.label() + "-" + orderSeq, businessDate,
                orderSeq, spec.idempotencyKey(), spec.totalAmount(), spec.manual(),
                spec.tableLabel(), spec.createdAt());
        // O28 승인대기(v0.6.10) — 손님 주문(C3)만 승인 전까지 대기시킨다. 수기 주문(O14, spec.manual()=true)은
        // 운영자가 직접 입력한 것이라 스스로 승인한 것과 같아 기본값 RECEIVED를 그대로 둔다
        if (!spec.manual()) {
            order.startPendingApproval();
        } else if (spec.noCooking()) {
            order.startAsNoCooking();
        }
        spec.items().forEach(order::addItem);
        return orderRepository.saveAndFlush(order);

    }

    /**
     * 자릿세 주문(명세서 밖, 파일럿) — 첫 메뉴 주문과 함께, 자릿세만 든 주문을 따로 만든다(조리할 것이 없어 완료·미결제).
     * 인원 선택만 하고 주문하지 않은 손님(메뉴 구경·마감된 부스)에게는 청구하지 않으려고 인원 선택 때가 아니라 주문 때 만든다.
     * 승인대기에 넣지 않는다: 승인은 "이 메뉴 주문을 받을지"이고, 거절돼도 자리를 쓴 값은 운영자가 따로 면제(취소)한다
     */
    private void saveSeatFeeOrder(OrderSpec spec, LocalDate businessDate) {
        SeatFee fee = spec.seatFee();
        int orderSeq = numberingService.nextSeq(spec.boothId(), businessDate);
        OrderEntity order = new OrderEntity(
                spec.boothId(), spec.sessionId(), spec.label() + "-" + orderSeq, businessDate, orderSeq, null,
                fee.perPerson() * fee.partySize(), false, spec.tableLabel(), spec.createdAt());
        order.startAsNoCooking();
        order.addItem(OrderItemEntity.seatFee(fee.perPerson(), fee.partySize()));
        orderRepository.saveAndFlush(order);
    }

    /**
     * 이 세션의 미결제 합계에 delta(음수)를 더해도 0 이상인가 — 아니면 409. 세션 행 또는 주문 행을 잠근 트랜잭션에서 부른다.
     * 결제 모달에서 쿠폰 줄 수량을 늘리는 경로(DashboardOrderActionService)도 같은 한도를 쓴다
     */
    public void requireUnpaidCovers(Long sessionId, long delta) {
        long unpaid = sessionId == null ? 0 : orderRepository.sumUnpaidAmountOfSession(sessionId);
        if (unpaid + delta < 0) {
            throw new InvalidStateException("할인 금액이 이 테이블의 미결제 금액(" + unpaid + "원)보다 커요.");
        }
    }

    /**
     * 이 세션의 자릿세가 이미 "처리"됐는가 — 자기 세션에 자릿세가 한 번이라도 생겼거나(운영자가 취소한 것 = 면제 포함),
     * 유휴 인계로 이어진 앞 세션이 같은 영업일에 자릿세를 냈거나 면제받았다(OrderRepository.inheritsSeatFeeFromIdleHandoff).
     * 부과 판정(save, 잠금 아래)·C3의 인원수 요구(PARTY_SIZE_REQUIRED)·C1의 seatFeeCharged·유휴 인계의 인원수 이어받기가
     * 모두 이 기준을 쓴다 — 면제받은 일행이 유휴 뒤 다시 찍었다고 다시 청구하지 않는다.
     * 앞 세션은 이미 종료돼 새 주문이 붙을 수 없으므로(410) 잠그지 않아도 그쪽 자릿세가 새로 생기지는 않는다
     */
    public boolean isSeatFeeHandled(Long sessionId, LocalDateTime at) {
        return orderRepository.existsSeatFeeItem(sessionId)
                || orderRepository.inheritsSeatFeeFromIdleHandoff(sessionId, numberingService.businessDateOf(at));
    }

    /** 첫 메뉴 주문에 함께 만들 자릿세 — 1인당 금액(부스 설정)과 세션 인원수. 둘 중 하나라도 0이면 만들지 않는다 */
    public record SeatFee(int perPerson, int partySize) {

        /** 부과할 게 없으면 null — OrderSpec.seatFee에 그대로 넣는다 */
        public static SeatFee of(int perPerson, Integer partySize) {
            return perPerson > 0 && partySize != null && partySize > 0 ? new SeatFee(perPerson, partySize) : null;
        }
    }

    /**
     * 저장에 필요한 값 묶음 — label은 정규화본(orderNo용), tableLabel은 원본 스냅샷(O10 표시용). manual: O14 수기 주문이면 true.
     * noCooking: 기타 항목(추가 자릿세·쿠폰 등)만 든 수기 주문 — 조리할 것이 없어 완료(DONE)로 시작한다.
     * seatFee: 손님 주문(C3)의 자릿세 — 이 세션에 처리된 자릿세가 없으면 save가 자릿세 주문을 따로 만든다. null이면 안 만든다
     */
    public record OrderSpec(Long boothId, Long sessionId, String label, String tableLabel,
                            String idempotencyKey, int totalAmount, List<OrderItemEntity> items,
                            LocalDateTime createdAt, boolean manual, boolean noCooking, SeatFee seatFee) {

        public OrderSpec(Long boothId, Long sessionId, String label, String tableLabel,
                         String idempotencyKey, int totalAmount, List<OrderItemEntity> items,
                         LocalDateTime createdAt, boolean manual) {
            this(boothId, sessionId, label, tableLabel, idempotencyKey, totalAmount, items, createdAt, manual, false, null);
        }

        /** 세션의 현재 테이블 라벨로 바꾼 사본 — 같거나(대부분) 알 수 없으면 그대로 둔다. 주문번호 접두(label)도 새 라벨로 다시 만든다 */
        OrderSpec withTableLabel(String current) {
            if (current == null || tableLabel == null || current.equals(tableLabel)) {
                return this;
            }
            return new OrderSpec(boothId, sessionId, OrderCreateService.normalizeTableLabel(current), current,
                    idempotencyKey, totalAmount, items, createdAt, manual, noCooking, seatFee);
        }
    }

}
