package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.global.error.NotImplementedException;
import com.boothlock.boothlock_server.tableqr.dto.TableAdminResponse;
import com.boothlock.boothlock_server.tableqr.service.TableAdminService;

import io.swagger.v3.oas.annotations.Operation;

import org.springframework.web.bind.annotation.*;

/**
 * [담당: 전형준] 테이블·QR·세션 — API 명세서 C1·O2~O6
 * 핵심 규칙: 토큰 2종 분리(tableToken=QR용/sessionToken=세션용, CSPRNG 128bit), 세션은 테이블 단위.
 */
@RestController
@RequestMapping("/api/v1")
public class TableController {

    private final TableAdminService tableAdminService;

    public TableController(TableAdminService tableAdminService) {
        this.tableAdminService = tableAdminService;
    }

    /** C1 세션 발급 (Must) — QR 토큰 검증, 활성 세션 있으면 복원(restored:true), 없으면 생성+OCCUPIED */
    @PostMapping("/table-sessions")
    public Object createSession() {
        // TODO(전형준): 명세서 C1
        throw new NotImplementedException("C1 세션 발급");
    }

    /** O2 테이블 일괄 등록 (Must) — count≤300, 라벨 정규화 후 6자·단독 M 금지, 토큰 자동 발급 */
    @PostMapping("/admin/tables/bulk")
    public Object bulkCreate() {
        // TODO(전형준): 명세서 O2
        throw new NotImplementedException("O2 테이블 일괄 등록");
    }

    /** O3 좌석 현황 (Should) — OCCUPIED+session:null = '정리 필요' */
    @GetMapping("/admin/tables")
    public Object getTables() {
        // TODO(전형준): 명세서 O3
        throw new NotImplementedException("O3 좌석 현황");
    }

    /** O4 QR 단건 다운로드 (Must) — ?format=png(기본)|pdf, 공식 도메인 문구 병기 */
    @GetMapping("/admin/tables/{tableId}/qr")
    public Object downloadQr(@PathVariable Long tableId) {
        // TODO(전형준): 명세서 O4
        throw new NotImplementedException("O4 QR 다운로드");
    }

    /** O4b QR 전체 일괄 PDF (Must) — 행사 준비용 */
    @GetMapping("/admin/tables/qr.pdf")
    public Object downloadAllQr() {
        // TODO(전형준): 명세서 O4b
        throw new NotImplementedException("O4b QR 일괄 PDF");
    }

    /** O5 QR 재발급 (Must) — 기존 토큰 즉시 폐기, 활성 세션은 유지 */
    @Operation(summary = "O5 QR 재발급", description = "테이블의 tableToken을 새로 발급해 기존 QR을 즉시 폐기한다. 활성 세션은 유지된다.")
    @PostMapping("/admin/tables/{tableId}/regenerate-token")
    public TableAdminResponse regenerateToken(@RequestHeader("Authorization") String authorization,
                                               @PathVariable Long tableId) {
        return tableAdminService.regenerateToken(authorization, tableId);
    }

    /** O6 퇴실·초기화 (Should) — 세션 무효(410), 멱등, 미결제 시 warning */
    @PostMapping("/admin/tables/{tableId}/checkout")
    public Object checkout(@PathVariable Long tableId) {
        // TODO(전형준): 명세서 O6
        throw new NotImplementedException("O6 퇴실");
    }
}
