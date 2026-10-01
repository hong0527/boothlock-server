package com.boothlock.boothlock_server.order.service;


import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.error.UnauthorizedException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemType;
import com.boothlock.boothlock_server.order.dto.OrderListResponse;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** C5 소비자 취소 — 내 세션의 RECEIVED+UNPAID 주문만 취소, 응답은 C4 단건 형태 (명세서 C5) */
@Service
public class OrderCancelService {

    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

    private final OrderRepository orderRepository;
    private final OrderSummaryAssembler assembler;
    private final OrderWriter orderWriter;
    private final BoothRepository boothRepository;

    public OrderCancelService(OrderRepository repository, OrderSummaryAssembler assembler,
                              OrderWriter orderWriter, BoothRepository boothRepository)
    {
        this.orderRepository = repository;
        this.assembler = assembler;
        this.orderWriter = orderWriter;
        this.boothRepository = boothRepository;
    }

    @Transactional
    public OrderListResponse.OrderSummary cancel(Long orderId, Long sessionId)
    {
        if(sessionId == null)
        {
            throw new UnauthorizedException("세션 정보가 없습니다.");
        }

        // 세션 조건을 쿼리에 넣어 남의 주문도 "없음"이 되게 한다 — 권한 오류를 주면 주문 존재가 드러난다
        // 행 잠금 — 조회 후 상태 변경 사이에 입금 확인(O11)이 커밋되면 "취소됐는데 PAID"가 된다 (audit2 B3)
        OrderEntity order = orderRepository.findByIdAndSessionIdForUpdate(orderId,sessionId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다."));

        // JVM 기본 시간대에 기대지 않는다 — java -jar 배포에는 -Duser.timezone이 붙지 않아 UTC 서버에서 9시간 어긋난다
        // 컬럼 정밀도(timestamp(6))에 맞춰 마이크로초로 — 응답과 재조회 값이 같아지도록 (OrderCreateService와 동일 이유)
        LocalDateTime now = LocalDateTime.now(KST_ZONE).truncatedTo(ChronoUnit.MICROS);
        // 취소할 수 없는 상태면 아래 cancelByCustomer가 원래 문구로 409를 낸다 — 최소금액 문구가 먼저 나가지 않게
        if (order.canCancel()) {
            requireMinOrderKept(order, sessionId, now);
        }
        order.cancelByCustomer(now);
        return assembler.assemble(order);
    }

    /**
     * 최소주문금액(명세서 밖, 파일럿) — 취소 뒤 일행의 메뉴 합계(OrderWriter.partyMenuAmount)가 0도 아니고 최소금액보다 적으면 409.
     * 최소금액을 넘긴 첫 주문 뒤에 소액을 추가하고 큰 주문만 취소해 최소금액을 피하는 우회를 막는다. 전부 취소(합계 0)는 된다.
     * 운영자 취소·항목 수정은 막지 않는다 — 운영자 판단이다
     */
    private void requireMinOrderKept(OrderEntity order, Long sessionId, LocalDateTime now) {
        int minOrderAmount = boothRepository.findById(order.getBoothId()).map(BoothEntity::getMinOrderAmount).orElse(0);
        if (minOrderAmount <= 0) {
            return;
        }
        long canceling = order.getItems().stream()
                .filter(item -> item.getItemType() == OrderItemType.MENU && !item.isCanceled())
                .mapToLong(OrderItemEntity::subtotal)
                .sum();
        long remaining = orderWriter.partyMenuAmount(sessionId, now) - canceling;
        if (remaining > 0 && remaining < minOrderAmount) {
            throw new InvalidStateException(String.format(
                    "이 주문을 취소하면 주문 합계가 최소주문금액(%,d원)보다 적어져요. 다른 주문을 먼저 취소해주세요.", minOrderAmount));
        }
    }

}
