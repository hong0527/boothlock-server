package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.dashboard.dto.CallAckResponse;
import com.boothlock.boothlock_server.dashboard.dto.CallRequest;
import com.boothlock.boothlock_server.dashboard.dto.CallResponse;
import com.boothlock.boothlock_server.dashboard.dto.CancelRequest;
import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.dashboard.dto.ItemQtyUpdateRequest;
import com.boothlock.boothlock_server.dashboard.dto.ManualOrderRequest;
import com.boothlock.boothlock_server.dashboard.dto.PaymentConfirmRequest;
import com.boothlock.boothlock_server.dashboard.dto.TablePaymentRequest;
import com.boothlock.boothlock_server.dashboard.dto.TablePaymentResponse;
import com.boothlock.boothlock_server.dashboard.service.CallService;
import com.boothlock.boothlock_server.dashboard.service.DashboardOrderActionService;
import com.boothlock.boothlock_server.dashboard.service.DashboardQueryService;
import com.boothlock.boothlock_server.dashboard.service.ManualOrderService;
import com.boothlock.boothlock_server.dashboard.service.TablePaymentService;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;
import com.boothlock.boothlock_server.tableqr.service.TableSessionAuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * [담당: 김재원] 운영자 대시보드·결제 처리·직원 호출 — API 명세서 O10~O15·C6·O21·O24
 * 핵심 규칙: 상태 전이는 §2 상태 머신만 허용(위반 시 409), 돈 관련 처리는 누가·언제 기록.
 * 운영자 API의 부스는 JWT로만 정하고, 손님 API(C6)의 세션은 X-Session-Token으로만 정한다 (§7-21).
 */
@Tag(name = "대시보드·결제·호출", description = "운영자 대시보드·결제 처리·직원 호출 (명세서 O10~O15·C6·O21·O24, 담당: 김재원)")
@RestController
@RequestMapping("/api/v1")
public class DashboardController {

    private final DashboardQueryService dashboardQueryService;
    private final DashboardOrderActionService orderActionService;
    private final CallService callService;
    private final ManualOrderService manualOrderService;
    private final TablePaymentService tablePaymentService;
    private final TableSessionAuthService sessionAuthService;

    public DashboardController(DashboardQueryService dashboardQueryService,
            DashboardOrderActionService orderActionService, CallService callService,
            ManualOrderService manualOrderService, TablePaymentService tablePaymentService,
            TableSessionAuthService sessionAuthService) {
        this.dashboardQueryService = dashboardQueryService;
        this.orderActionService = orderActionService;
        this.callService = callService;
        this.manualOrderService = manualOrderService;
        this.tablePaymentService = tablePaymentService;
        this.sessionAuthService = sessionAuthService;
    }

    /** O10 실시간 대시보드 (Must) — 주문+미확인 호출 한 번에, 폴링 3~5초, q=주문번호 검색 */
    @Operation(summary = "O10 실시간 대시보드",
            description = "JWT 부스의 주문 목록과 미확인 호출을 한 번에 조회한다. 폴링 주기 3~5초 권장. "
                    + "businessDate를 생략하면 현재 영업일(06:00 경계). tableId를 주면 그 테이블의 모든 세션 주문만 반환하고, "
                    + "미존재·타 부스 테이블은 404. activeSessionOnly=true를 tableId와 함께 주면 그 테이블의 종료 안 된 세션 주문만 "
                    + "(세션이 없으면 빈 목록). tableId 없이 activeSessionOnly=true는 400.")
    @GetMapping("/admin/orders")
    public DashboardResponse getDashboard(
            @RequestHeader("Authorization") String authorization,
            // 임시 파라미터 시절 클라이언트가 조용히 남의 부스를 지정한 채 동작하지 않게, 보내면 명시적으로 400 (§7-21)
            @Parameter(hidden = true)
            @RequestParam(name = "boothId", required = false) String legacyBoothId,
            @Parameter(description = "주문 상태 필터")
            @RequestParam(required = false) OrderStatus status,
            @Parameter(description = "결제 상태 필터")
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @Parameter(description = "영업일 필터 (YYYY-MM-DD) — 생략 시 현재 영업일")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @Parameter(description = "주문번호 부분 검색 (예: A3-17)")
            @RequestParam(required = false) String q,
            @Parameter(description = "특정 테이블의 주문만 (POS 배치도에서 테이블 클릭 시 사용, v0.5 신설)")
            @RequestParam(required = false) Long tableId,
            @Parameter(description = "tableId와 함께 true면 그 테이블의 종료 안 된 세션 주문만 (결제 모달용). tableId 없이 true면 400")
            @RequestParam(required = false, defaultValue = "false") boolean activeSessionOnly) {
        rejectLegacyParam("boothId", legacyBoothId, "부스는 로그인 토큰으로 식별합니다.");
        return dashboardQueryService.getDashboard(
                authorization, status, paymentStatus, businessDate, q, tableId, activeSessionOnly);
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

    /**
     * O14 수기 주문 (Should) — 검증은 소비자 주문(C3)과 동일, isManual 표시, 미지정 시 M-{통산}
     *
     * <p><b>응답 형태가 이 컨트롤러의 다른 액션과 다르다.</b> 나머지는 전부
     * {@code DashboardResponse.OrderSummary}를 주는데 여기만 {@code OrderCreateResponse}(손님 주문 C3 형태)다.
     * 그쪽에는 {@code manual}·{@code tableLabel}·{@code items[].itemId}가 없고 대신
     * {@code payment.method}·{@code items[].subtotal}이 있다.
     *
     * <p>지금은 프론트가 이 응답 본문을 버리고 목록을 다시 읽어서 문제가 없다. 하지만 "응답으로 바로
     * 보드를 갱신하자"는 최적화를 넣으면 수기 주문 카드에서 수기 배지와 테이블 라벨이 사라지고,
     * 결제 모달의 항목 +/-·개별 취소가 {@code itemId} 없이 엉뚱한 항목을 가리킨다.
     * 그렇게 바꿀 거면 응답 타입을 먼저 OrderSummary로 맞춰야 한다.
     */
    @Operation(summary = "O14 수기 주문", description = "tableId를 지정하면 그 테이블 세션에 귀속시킨다(없으면 자동 생성). "
            + "생략하면 테이블 미지정 주문(M-통산번호)으로 만든다. 검증은 소비자 주문(C3)과 동일하며, "
            + "품절·마감·잘못된 요청이면 세션을 만들지 않는다. tableId는 JWT 부스 소속이어야 한다(아니면 404).")
    @PostMapping("/admin/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderCreateResponse manualOrder(
            @RequestHeader("Authorization") String authorization,
            @RequestBody ManualOrderRequest request) {
        return manualOrderService.create(authorization, request);
    }

    /** O24 테이블 일괄 입금 확인 — 그 테이블 활성 세션의 미결제 주문 전부, 화면 합계와 다르면 409 */
    @Operation(summary = "O24 테이블 일괄 입금 확인",
            description = "테이블의 종료 안 된 세션에 속한 미결제(RECEIVED·DONE && UNPAID) 주문을 한 번에 입금 확인한다. "
                    + "expectedTotal이 서버 합계와 다르거나 대상이 없으면 409, 한 건이라도 실패하면 전체 롤백 후 409. "
                    + "타 부스·미존재 테이블은 404.")
    @PostMapping("/admin/orders/table-payment")
    public TablePaymentResponse confirmTablePayment(
            @RequestHeader("Authorization") String authorization,
            @RequestBody TablePaymentRequest request) {
        return tablePaymentService.confirmTablePayment(authorization, request);
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

    /**
     * 되돌리기 (명세서 밖) — 완료·취소 탭의 주문을 진행(RECEIVED) 탭으로 되돌린다. 결제/환불 상태(paymentStatus)는
     * 손대지 않는다. 완료·취소 상태가 아니거나, 이미 삭제된 주문이거나, 모든 항목이 취소돼 빈 주문이면 409.
     */
    @Operation(summary = "되돌리기", description = "완료·취소된 주문을 다시 접수(RECEIVED) 상태로 되돌린다. 결제 상태는 변경하지 않는다. "
            + "완료·취소 상태가 아니거나 이미 삭제됐거나 모든 항목이 취소된 주문이면 409.")
    @PostMapping("/admin/orders/{orderId}/restore")
    public DashboardResponse.OrderSummary restore(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long orderId) {
        return orderActionService.restore(authorization, orderId);
    }

    /**
     * 취소 주문 삭제 (명세서 밖) — 실제 데이터 삭제가 아니라 대시보드 목록에서만 숨긴다. 결제·환불·정산 데이터에는
     * 영향이 없다. CANCELED가 아니거나 이미 삭제된 주문이면 409.
     */
    @Operation(summary = "취소 주문 삭제", description = "취소된 주문을 주문현황 목록·탭 건수에서 제외한다. 주문·항목·결제·환불 데이터는 "
            + "그대로 보존되며 정산에는 영향을 주지 않는다. CANCELED가 아니거나 이미 삭제된 주문은 409.")
    @DeleteMapping("/admin/orders/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCanceledOrder(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long orderId) {
        orderActionService.hideCanceledOrder(authorization, orderId);
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
    @Operation(summary = "C6 직원 호출", description = "테이블에서 직원을 호출한다. 세션은 X-Session-Token 헤더로 식별한다"
            + "(누락 401, 미존재·종료 토큰 410). 같은 세션 30초 내 재호출은 429.")
    @PostMapping("/calls")
    @ResponseStatus(HttpStatus.CREATED)
    public CallResponse call(
            @Parameter(description = "테이블 세션 토큰", required = true)
            @RequestHeader("X-Session-Token") String sessionToken,
            @Parameter(hidden = true)
            @RequestParam(name = "sessionId", required = false) String legacySessionId,
            @Valid @RequestBody CallRequest request) {
        rejectLegacyParam("sessionId", legacySessionId, "세션은 X-Session-Token 헤더로 식별합니다.");
        // C3·C4·C5와 같은 인증 계층 — 헤더 누락 401, 미존재·종료 토큰 410, 호출도 세션 활동으로 기록된다
        return callService.create(sessionAuthService.authenticate(sessionToken).sessionId(), request);
    }

    /** O15 호출 확인 (Should) — 멱등 */
    @Operation(summary = "O15 호출 확인", description = "직원 호출을 확인 처리한다. 이미 확인된 호출도 200. 타 부스·미존재 호출은 404.")
    @PatchMapping("/admin/calls/{callId}/ack")
    @ResponseStatus(HttpStatus.OK)
    public CallAckResponse ackCall(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long callId) {
        return callService.ack(authorization, callId);
    }

    /** 인증 전환(§7-21)으로 없앤 임시 파라미터 — 값을 신뢰하지도, 조용히 무시하지도 않는다 */
    private void rejectLegacyParam(String name, String value, String guide) {
        if (value != null) {
            throw new InvalidRequestException(name + " 파라미터는 더 이상 받지 않습니다. " + guide);
        }
    }
}
