package com.boothlock.boothlock_server.order.service;


import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.error.UnauthorizedException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.dto.OrderListResponse;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** C5 소비자 취소 — 내 세션의 RECEIVED+UNPAID 주문만 취소, 응답은 C4 단건 형태 (명세서 C5) */
@Service
public class OrderCancelService {

    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

    private final OrderRepository orderRepository;
    private final OrderSummaryAssembler assembler;

    public OrderCancelService(OrderRepository repository, OrderSummaryAssembler assembler)
    {
        this.orderRepository = repository;
        this.assembler = assembler;
    }

    @Transactional
    public OrderListResponse.OrderSummary cancel(Long orderId, Long sessionId)
    {
        if(sessionId == null)
        {
            throw new UnauthorizedException("세션 정보가 없습니다.");
        }

        // 세션 조건을 쿼리에 넣어 남의 주문도 "없음"이 되게 한다 — 권한 오류를 주면 주문 존재가 드러난다
        OrderEntity order = orderRepository.findByIdAndSessionId(orderId,sessionId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다."));

        // JVM 기본 시간대에 기대지 않는다 — java -jar 배포에는 -Duser.timezone이 붙지 않아 UTC 서버에서 9시간 어긋난다
        order.cancelByCustomer(LocalDateTime.now(KST_ZONE));
        return assembler.assemble(order);
    }

}
