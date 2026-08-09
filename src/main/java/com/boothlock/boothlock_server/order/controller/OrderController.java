package com.boothlock.boothlock_server.order.controller;

import com.boothlock.boothlock_server.global.error.NotImplementedException;

import org.springframework.web.bind.annotation.*;

/**
 * [담당: 홍화수] 소비자 주문 — API 명세서 C3·C4·C5
 * 구현 패턴은 README "공통 개발 패턴", 테이블 구조는 docs/DB스키마_v1.2.md 참조 (정본).
 */
@RestController
@RequestMapping("/api/v1")
public class OrderController {

    /** C3 주문 생성 (Must) — Idempotency-Key 헤더 필수, 검증 6단계, 서버 가격 재계산, 채번 A3-17 */
    @PostMapping("/orders")
    public Object createOrder() {
        // TODO(홍화수): 명세서 C3 — 요청 DTO(items[{menuId,qty}]) 정의 후 구현
        throw new NotImplementedException("C3 주문 생성");
    }

    /** C4 내 주문 조회 (Must) — 폴링 5~10초, canCancel은 서버가 계산 */
    @GetMapping("/orders")
    public Object getOrders() {
        // TODO(홍화수): 명세서 C4
        throw new NotImplementedException("C4 주문 조회");
    }

    /** C5 소비자 취소 (Should) — RECEIVED+UNPAID일 때만, 아니면 409 INVALID_STATE */
    @PostMapping("/orders/{orderId}/cancel")
    public Object cancelOrder(@PathVariable Long orderId) {
        // TODO(홍화수): 명세서 C5
        throw new NotImplementedException("C5 소비자 취소");
    }
}
