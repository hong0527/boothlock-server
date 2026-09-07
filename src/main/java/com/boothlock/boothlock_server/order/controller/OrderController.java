package com.boothlock.boothlock_server.order.controller;

import com.boothlock.boothlock_server.order.dto.OrderCreateRequest;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;
import com.boothlock.boothlock_server.order.dto.OrderCreationResult;
import com.boothlock.boothlock_server.order.dto.OrderListResponse;
import com.boothlock.boothlock_server.order.service.OrderCancelService;
import com.boothlock.boothlock_server.order.service.OrderCreateService;
import com.boothlock.boothlock_server.order.service.OrderQueryService;
import com.boothlock.boothlock_server.tableqr.dto.AuthenticatedSession;
import com.boothlock.boothlock_server.tableqr.service.TableSessionAuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * [담당: 홍화수] 소비자 주문 — API 명세서 C3·C4·C5
 * 세 API 모두 X-Session-Token 헤더로 세션을 식별한다 (§1.2). 헤더 누락은 401, 만료·미존재 토큰은 410 (§1.4).
 * 구현 패턴은 README "공통 개발 패턴", 테이블 구조는 docs/DB스키마_v1.2.md 참조 (정본).
 */
@Tag(name = "소비자 주문", description = "소비자 주문 생성·조회·취소 (명세서 C3·C4·C5, 담당: 홍화수)")
@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private static final String SESSION_HEADER = "X-Session-Token";

    private final TableSessionAuthService sessionAuthService;
    private final OrderCreateService orderCreateService;
    private final OrderQueryService orderQueryService;
    private final OrderCancelService orderCancelService;

    public OrderController(TableSessionAuthService sessionAuthService,
                           OrderCreateService orderCreateService,
                           OrderQueryService orderQueryService,
                           OrderCancelService orderCancelService) {
        this.sessionAuthService = sessionAuthService;
        this.orderCreateService = orderCreateService;
        this.orderQueryService = orderQueryService;
        this.orderCancelService = orderCancelService;
    }

    /**
     * C3 주문 생성 (Must) — Idempotency-Key 헤더 필수, 검증 6단계, 서버 가격 재계산, 채번 A3-17.
     * 새로 만들었으면 201, 같은 멱등키 재요청이면 기존 주문을 200으로 돌려준다 (명세서 C3).
     */
    @Operation(summary = "C3 주문 생성", description = "세션의 부스에 주문을 만든다. 같은 Idempotency-Key 재요청은 기존 주문을 200으로 반환한다.")
    @PostMapping("/orders")
    public ResponseEntity<OrderCreateResponse> createOrder(
            @Parameter(description = "테이블 세션 토큰", required = true)
            @RequestHeader(SESSION_HEADER) String sessionToken,
            @Parameter(description = "주문 시도마다 클라이언트가 생성하는 UUID. 재시도 시 같은 키 재사용", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody OrderCreateRequest request) {
        AuthenticatedSession session = sessionAuthService.authenticate(sessionToken);
        OrderCreationResult result = orderCreateService.create(
                session.boothId(), session.sessionId(), session.tableLabel(), idempotencyKey, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.response());
    }

    /** C4 내 주문 조회 (Must) — 폴링 5~10초, canCancel은 서버가 계산. 폴링도 세션 활동으로 인정된다(인증 계층이 touch) */
    @Operation(summary = "C4 내 주문 조회", description = "현재 세션의 주문 전체를 최신순으로 조회한다. 폴링 주기 5~10초 권장.")
    @GetMapping("/orders")
    public OrderListResponse getOrders(
            @Parameter(description = "테이블 세션 토큰", required = true)
            @RequestHeader(SESSION_HEADER) String sessionToken) {
        AuthenticatedSession session = sessionAuthService.authenticate(sessionToken);
        return orderQueryService.getOrders(session.sessionId());
    }

    /** C5 소비자 취소 (Should) — RECEIVED+UNPAID일 때만, 아니면 409 INVALID_STATE */
    @Operation(summary = "C5 소비자 취소", description = "내 세션의 미입금 접수 주문만 취소한다. 응답은 C4 단건 형태.")
    @PostMapping("/orders/{orderId}/cancel")
    public OrderListResponse.OrderSummary cancelOrder(
            @PathVariable Long orderId,
            @Parameter(description = "테이블 세션 토큰", required = true)
            @RequestHeader(SESSION_HEADER) String sessionToken) {
        AuthenticatedSession session = sessionAuthService.authenticate(sessionToken);
        return orderCancelService.cancel(orderId, session.sessionId());
    }
}
