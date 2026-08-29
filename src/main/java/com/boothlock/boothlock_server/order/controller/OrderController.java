package com.boothlock.boothlock_server.order.controller;

import com.boothlock.boothlock_server.global.error.NotImplementedException;

import com.boothlock.boothlock_server.order.dto.OrderListResponse;
import com.boothlock.boothlock_server.order.service.OrderCancelService;
import com.boothlock.boothlock_server.order.service.OrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * [담당: 홍화수] 소비자 주문 — API 명세서 C3·C4·C5
 * 구현 패턴은 README "공통 개발 패턴", 테이블 구조는 docs/DB스키마_v1.2.md 참조 (정본).
 */

@Tag(name = "소비자 주문", description = "소비자 주문 생성·조회·취소 (명세서 C3·C4·C5, 담당: 홍화수)")
@RestController
@RequestMapping("/api/v1")
public class OrderController {
    private final OrderQueryService orderQueryService;
    private final OrderCancelService orderCancelService;

    public OrderController(OrderQueryService orderQueryService, OrderCancelService orderCancelService) {
        this.orderQueryService = orderQueryService;
        this.orderCancelService = orderCancelService;
    }

    /** C3 주문 생성 (Must) — Idempotency-Key 헤더 필수, 검증 6단계, 서버 가격 재계산, 채번 A3-17 */
    @PostMapping("/orders")
    public Object createOrder() {
        // TODO(홍화수): 메뉴 파트(권희원) 머지 후 MenuLookup 실구현과 함께 연결 — 서비스(OrderCreateService)는 완성돼 있다
        throw new NotImplementedException("C3 주문 생성");
    }

    /** C4 내 주문 조회 (Must) — 폴링 5~10초, canCancel은 서버가 계산 */
    @Operation(summary = "C4 내 주문 조회", description = "현재 세션의 주문 전체를 최신순으로 조회한다. 폴링 주기 5~10초 권장.")
    @GetMapping("/orders")
    public OrderListResponse getOrders(
            // TODO(홍화수): sessionId는 세션 인증(전형준 C1) 연동 전까지 임시 쿼리 파라미터 — 연동되면 토큰에서 추출하도록 교체 (O10 boothId와 같은 방식)
            @Parameter(description = "세션 ID (임시: 세션 인증 연동 전까지 직접 지정)", required = true)
            @RequestParam Long sessionId
    ){
        return orderQueryService.getOrders(sessionId);
    }

    /** C5 소비자 취소 (Should) — RECEIVED+UNPAID일 때만, 아니면 409 INVALID_STATE */
    @Operation(summary = "C5 소비자 취소", description = "내 세션의 미입금 접수 주문만 취소한다. 응답은 C4 단건 형태.")
    @PostMapping("/orders/{orderId}/cancel")
    public OrderListResponse.OrderSummary cancelOrder(
            @PathVariable Long orderId,
            // TODO(홍화수): sessionId는 세션 인증(전형준 C1) 연동 전까지 임시 쿼리 파라미터 — 연동되면 토큰에서 추출하도록 교체
            @Parameter(description = "세션 ID (임시: 세션 인증 연동 전까지 직접 지정)", required = true)
            @RequestParam Long sessionId
    ){
        return orderCancelService.cancel(orderId,sessionId);
    }

}
