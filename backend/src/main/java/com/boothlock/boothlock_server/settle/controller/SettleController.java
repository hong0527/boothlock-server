package com.boothlock.boothlock_server.settle.controller;

import com.boothlock.boothlock_server.settle.dto.FeedbackRequest;
import com.boothlock.boothlock_server.settle.dto.SalesStatsResponse;
import com.boothlock.boothlock_server.settle.service.FeedbackService;
import com.boothlock.boothlock_server.settle.service.SalesStatsService;
import com.boothlock.boothlock_server.settle.service.SettlementCsvService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * [담당: 백지연] 정산·통계·피드백 — API 명세서 O18·O19·O20
 * 핵심 규칙: O18 매출은 PAID 기준·영업일(06:00~익일05:59). O19 CSV는 PAID 확정 매출만,
 * 입금 확인 시각(approvedAt, KST) 구간 기준(v0.6.5) — 행=주문항목·BOM·수식주입 방지.
 */
@Tag(name = "정산·통계·피드백", description = "운영자 정산·통계·피드백 (명세서 O18·O19·O20, 담당: 백지연)")
@RestController
@RequestMapping("/api/v1")
public class SettleController {

    private final FeedbackService feedbackService;
    private final SalesStatsService salesStatsService;
    private final SettlementCsvService settlementCsvService;

    public SettleController(FeedbackService feedbackService, SalesStatsService salesStatsService,
            SettlementCsvService settlementCsvService) {
        this.feedbackService = feedbackService;
        this.salesStatsService = salesStatsService;
        this.settlementCsvService = settlementCsvService;
    }

    /** O18 매출 집계 (Should) — 수단별 분리, 환불필요·환불됨 별도 집계 */
    @Operation(summary = "O18 매출 집계 조회", description = "영업일별 정상 매출과 결제 수단별 매출, 환불 현황을 조회한다.")
    @GetMapping("/admin/stats/sales")
    public SalesStatsResponse getSales(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return salesStatsService.getSales(authorization, date);
    }

    /** O19 정산 CSV (Should) — 시작~마감(KST) 구간에 입금 확인된 PAID 매출만, UTF-8 BOM. O18과 같은 ADMIN 전용 */
    @Operation(summary = "O19 정산 CSV 다운로드",
            description = "시작~마감 일시(KST) 구간에 입금 확인(approvedAt)된 PAID 주문 항목을 CSV로 내려받는다.")
    @GetMapping("/admin/reports/settlement.csv")
    public ResponseEntity<byte[]> downloadSettlement(
            @RequestHeader("Authorization") String authorization,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt) {
        SettlementCsvService.Result result = settlementCsvService.generate(authorization, startAt, endAt);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }

    /** O20 운영자 피드백 (Should) — 부스락 서비스 평가 (소비자 설문 아님) */
    @Operation(summary = "O20 운영자 피드백 저장", description = "운영자가 부스락 서비스에 대한 평가와 의견을 저장한다.")
    @PostMapping("/admin/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public Long feedback(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody FeedbackRequest request) {
        return feedbackService.saveFeedback(authorization, request);
    }
}
