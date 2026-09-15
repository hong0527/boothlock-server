package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.dashboard.dto.CallRequest;
import com.boothlock.boothlock_server.dashboard.dto.CallResponse;
import com.boothlock.boothlock_server.dashboard.dto.CancelRequest;
import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.dashboard.dto.ItemQtyUpdateRequest;
import com.boothlock.boothlock_server.dashboard.dto.ManualOrderRequest;
import com.boothlock.boothlock_server.dashboard.dto.PaymentConfirmRequest;
import com.boothlock.boothlock_server.dashboard.service.CallService;
import com.boothlock.boothlock_server.dashboard.service.DashboardOrderActionService;
import com.boothlock.boothlock_server.dashboard.service.DashboardQueryService;
import com.boothlock.boothlock_server.dashboard.service.ManualOrderService;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * [담당: 김재원] 운영자 대시보드·결제 처리·직원 호출 — API 명세서 O10~O15·C6·O21
 * 핵심 규칙: 상태 전이는 §2 상태 머신만 허용(위반 시 409), 돈 관련 처리는 누가·언제 기록.
 */
@Tag(name = "대시보드·결제·호출", description = "운영자 대시보드·결제 처리·직원 호출 (명세서 O10~O15·C6·O21, 담당: 김재원)")
@RestController
@RequestMapping("/api/v1")
public class DashboardController {

    private final DashboardQueryService dashboardQueryService;
    private final DashboardOrderActionService orderActionService;
    private final CallService callService;
    private final ManualOrderService manualOrderService;

    public DashboardController(DashboardQueryService dashboardQueryService,
            DashboardOrderActionService orderActionService, CallService callService,
            ManualOrderService manualOrderService) {
        this.dashboardQueryService = dashboardQueryService;
        this.orderActionService = orderActionService;
        this.callService = callService;
        this.manualOrderService = manualOrderService;
    }

    /** O10 실시간 대시보드 (Must) — 주문+미확인 호출 한 번에, 폴링 3~5초, q=주문번호 검색 */
    @Operation(summary = "O10 실시간 대시보드", description = "주문 목록과 미확인 호출을 한 번에 조회한다. 폴링 주기 3~5초 권장.")
    @GetMapping("/admin/orders")
    public DashboardResponse getDashboard(
            @RequestHeader("Authorization") String authorization,
            @Parameter(description = "주문 상태 필터")
            @RequestParam(required = false) OrderStatus status,
            @Parameter(description = "결제 상태 필터")
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @Parameter(description = "영업일 필터 (YYYY-MM-DD)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @Parameter(description = "주문번호 부분 검색 (예: A3-17)")
            @RequestParam(required = false) String q,
            @Parameter(description = "특정 테이블의 주문만 (POS 배치도에서 테이블 클릭 시 사용, v0.5 신설)")
            @RequestParam(required = false) Long tableId) {
        return dashboardQueryService.getDashboard(authorization, status, paymentStatus, businessDate, q, tableId);
    }

    /** O11 입금 확인 (Must) — UNPAID→PAID, 승인자·승인시각 자동 기록 */
    @Operation(summary = "O11 입금 확인", description = "주문의 입금을 확인해 결제 상태를 PAID로 전환한다. 이미 결제된 주문은 409.")
    @PatchMapping("/admin/orders/{orderId}/payment")
    public DashboardResponse.OrderSummary confirmPayment(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long orderId,
            @Valid @RequestBody PaymentConfirmRequest request) {
        return orderActionService.confirmPayment(authorization, orderId, request.method());
    }

    /** O12 완료 처리 (Must) — RECEIVED→DONE. 미결제여도 가능하나 '미결제 완료' 뱃지 */
    @Operation(summary = "O12 완료 처리", description = "조리·전달이 끝난 주문을 완료 처리한다. 결제 여부와 무관하게 가능.")
    @PatchMapping("/admin/orders/{orderId}/complete")
    public DashboardResponse.OrderSummary complete(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long orderId) {
        return orderActionService.complete(authorization, orderId);
    }

    /** O13 운영자 취소 (Should) — 전 단계 가능, 사유 필수, 취소자 기록, PAID면 REFUND_NEEDED 전환 */
    @Operation(summary = "O13 운영자 취소", description = "사유를 남기고 주문을 취소한다. 입금된 주문은 REFUND_NEEDED로 전환. 이미 취소된 주문은 409.")
    @PostMapping("/admin/orders/{orderId}/cancel")
    public DashboardResponse.OrderSummary cancelByStaff(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long orderId,
            @Valid @RequestBody CancelRequest request) {
        return orderActionService.cancelByStaff(authorization, orderId, request.reason());
    }

    /** O14 수기 주문 (Should) — 검증은 소비자 주문(C3)과 동일, isManual 표시, 미지정 시 M-{통산} */
    @Operation(summary = "O14 수기 주문", description = "tableId를 지정하면 그 테이블 세션에 귀속시킨다(없으면 자동 생성). "
            + "생략하면 테이블 미지정 주문(M-통산번호)으로 만든다. 검증은 소비자 주문(C3)과 동일.")
    @PostMapping("/admin/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderCreateResponse manualOrder(
            @RequestHeader("Authorization") String authorization,
            @RequestBody ManualOrderRequest request) {
        return manualOrderService.create(authorization, request);
    }

    /** O6 결제 모달 수량 +/- (명세서 밖) — RECEIVED+UNPAID일 때만, 아니면 409 */
    @Operation(summary = "O6 항목 수량 변경", description = "결제 모달에서 항목 수량을 바꾼다. 접수+미결제 상태일 때만 가능.")
    @PatchMapping("/admin/orders/{orderId}/items/{itemId}")
    public DashboardResponse.OrderSummary updateItemQty(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long orderId,
            @PathVariable Long itemId,
            @Valid @RequestBody ItemQtyUpdateRequest request) {
        return orderActionService.updateItemQty(authorization, orderId, itemId, request.qty());
    }

    /** O6 결제 모달 개별 항목 취소 (명세서 밖) — 남은 항목이 없으면 주문 전체가 취소된다 */
    @Operation(summary = "O6 항목 취소", description = "결제 모달에서 항목 하나를 취소한다. 마지막 남은 항목이면 주문 전체가 취소 처리된다.")
    @PostMapping("/admin/orders/{orderId}/items/{itemId}/cancel")
    public DashboardResponse.OrderSummary cancelItem(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long orderId,
            @PathVariable Long itemId) {
        return orderActionService.cancelItem(authorization, orderId, itemId);
    }

    /** O21 환불 완료 (Should·ADMIN 전용) — REFUND_NEEDED→REFUNDED, 처리자 기록 */
    @Operation(summary = "O21 환불 완료", description = "REFUND_NEEDED 상태의 주문을 환불 완료 처리한다. ADMIN 전용. REFUND_NEEDED가 아니면 409.")
    @PostMapping("/admin/orders/{orderId}/refund-done")
    public DashboardResponse.OrderSummary refundDone(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long orderId) {
        return orderActionService.refundDone(authorization, orderId);
    }

    /** C6 직원 호출 (Should) — reason: HELP|WATER|ETC, 같은 세션 30초 재호출 제한(429) */
    @Operation(summary = "C6 직원 호출", description = "테이블에서 직원을 호출한다. 같은 세션 30초 내 재호출은 429.")
    @PostMapping("/calls")
    @ResponseStatus(HttpStatus.CREATED)
    public CallResponse call(
            // TODO(김재원): sessionId는 세션 인증(전형준 C1) 연동 전까지 임시 쿼리 파라미터 — 연동되면 토큰에서 추출하도록 교체
            @Parameter(description = "세션 ID (임시: 세션 인증 연동 전까지 직접 지정)", required = true)
            @RequestParam Long sessionId,
            @Valid @RequestBody CallRequest request) {
        return callService.create(sessionId, request);
    }

    /** O15 호출 확인 (Should) — 멱등 */
    @Operation(summary = "O15 호출 확인", description = "직원 호출을 확인 처리한다. 이미 확인된 호출도 200.")
    @PatchMapping("/admin/calls/{callId}/ack")
    @ResponseStatus(HttpStatus.OK)
    public void ackCall(@PathVariable Long callId) {
        callService.ack(callId);
    }
}
