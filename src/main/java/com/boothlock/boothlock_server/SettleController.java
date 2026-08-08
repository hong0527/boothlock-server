package com.boothlock.boothlock_server;

import org.springframework.web.bind.annotation.*;

/**
 * [담당: 백지연] 정산·통계·피드백 — API 명세서 O18·O19·O20
 * 핵심 규칙: 매출은 PAID 기준, date=영업일(06:00~익일05:59), CSV는 행=주문항목·BOM·수식주입 방지.
 */
@RestController
@RequestMapping("/api/v1")
public class SettleController {

    /** O18 매출 집계 (Should) — 수단별 분리, 환불필요·환불됨 별도 집계 */
    @GetMapping("/admin/stats/sales")
    public Object getSales() {
        // TODO(백지연): 명세서 O18 — ?date=영업일 (생략 시 현재 영업일)
        throw new NotImplementedException("O18 매출 집계");
    }

    /** O19 정산 CSV (Should) — 전체 원장(승인·취소·환불 이력 컬럼), UTF-8 BOM */
    @GetMapping("/admin/reports/settlement.csv")
    public Object downloadSettlement() {
        // TODO(백지연): 명세서 O19
        throw new NotImplementedException("O19 정산 CSV");
    }

    /** O20 운영자 피드백 (Should) — 부스락 서비스 평가 (소비자 설문 아님) */
    @PostMapping("/admin/feedback")
    public Object feedback() {
        // TODO(백지연): 명세서 O20
        throw new NotImplementedException("O20 피드백");
    }
}
