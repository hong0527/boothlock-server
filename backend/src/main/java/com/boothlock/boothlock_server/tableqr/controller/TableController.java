package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.tableqr.dto.QrFile;
import com.boothlock.boothlock_server.tableqr.dto.TableAdminResponse;
import com.boothlock.boothlock_server.tableqr.dto.TableBulkCreateRequest;
import com.boothlock.boothlock_server.tableqr.dto.TableBulkCreateResponse;
import com.boothlock.boothlock_server.tableqr.dto.TableCheckoutResponse;
import com.boothlock.boothlock_server.tableqr.dto.TableGridPositionRequest;
import com.boothlock.boothlock_server.tableqr.dto.TablePositionRequest;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionCreateRequest;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionResponse;
import com.boothlock.boothlock_server.tableqr.dto.TableStatusListResponse;
import com.boothlock.boothlock_server.tableqr.dto.TableStatusResponse;
import com.boothlock.boothlock_server.tableqr.service.TableAdminService;
import com.boothlock.boothlock_server.tableqr.service.TableQrService;
import com.boothlock.boothlock_server.tableqr.service.TableSessionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * [담당: 전형준] 테이블·QR·세션 — API 명세서 C1·O2~O6
 * 핵심 규칙: 토큰 2종 분리(tableToken=QR용/sessionToken=세션용, CSPRNG 128bit), 세션은 테이블 단위.
 */
@Tag(name = "테이블·QR·세션", description = "테이블·QR·세션 (명세서 C1·O2~O6·O4b, 담당: 전형준)")
@RestController
@RequestMapping("/api/v1")
public class TableController {

    private final TableSessionService tableSessionService;
    private final TableAdminService tableAdminService;
    private final TableQrService tableQrService;

    public TableController(TableSessionService tableSessionService,
                            TableAdminService tableAdminService,
                            TableQrService tableQrService) {
        this.tableSessionService = tableSessionService;
        this.tableAdminService = tableAdminService;
        this.tableQrService = tableQrService;
    }

    /** C1 세션 발급 (Must) — QR 토큰 검증, 활성 세션 있으면 복원(restored:true), 없으면 생성+OCCUPIED */
    @Operation(summary = "C1 세션 발급", description = "QR의 테이블 토큰을 검증해 세션 토큰을 발급한다. 활성 세션이 있으면 복원(restored:true)한다.")
    @PostMapping("/table-sessions")
    public TableSessionResponse createSession(@Valid @RequestBody TableSessionCreateRequest request) {
        return tableSessionService.createOrRestore(request);
    }

    /** O2 테이블 일괄 등록 (Must) — count≤300, 라벨 정규화 후 6자·단독 M 금지, 토큰 자동 발급 */
    @Operation(summary = "O2 테이블 일괄 등록",
            description = "count+labelPrefix 또는 labels 중 하나로 테이블을 일괄 등록한다(최대 300건). "
                    + "라벨은 정규화 후 6자·단독 M 금지·부스 내 중복을 금지하고, tableToken은 추측 불가한 값으로 자동 발급한다.")
    @PostMapping("/admin/tables/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    public TableBulkCreateResponse bulkCreate(@RequestHeader("Authorization") String authorization,
                                               @RequestBody TableBulkCreateRequest request) {
        return tableAdminService.bulkCreate(authorization, request);
    }

    /** O3 좌석 현황 (Should) — OCCUPIED+session:null = '정리 필요' */
    @Operation(summary = "O3 좌석 현황",
            description = "부스의 모든 테이블 상태를 라벨 순으로 반환한다. OCCUPIED인데 활성 세션이 없으면 needsCleanup=true('정리 필요').")
    @GetMapping("/admin/tables")
    public TableStatusListResponse getTables(@RequestHeader("Authorization") String authorization) {
        return tableAdminService.getTableStatuses(authorization);
    }

    /** O4 QR 단건 다운로드 (Must) — ?format=png(기본)|pdf, 공식 도메인 문구 병기 */
    @Operation(summary = "O4 QR 단건 다운로드",
            description = "테이블 하나의 QR을 PNG(기본) 또는 PDF로 내려받는다. 이미지에는 공식 도메인 문구를 병기해 위조 QR을 가려낸다.")
    @GetMapping("/admin/tables/{tableId}/qr")
    public ResponseEntity<byte[]> downloadQr(@RequestHeader("Authorization") String authorization,
                                              @PathVariable Long tableId,
                                              @RequestParam(required = false) String format) {
        return toResponse(tableQrService.downloadSingle(authorization, tableId, format));
    }

    /** O4b QR 전체 일괄 PDF (Must) — 행사 준비용 */
    @Operation(summary = "O4b QR 전체 일괄 PDF",
            description = "부스의 모든 테이블 QR을 라벨 순으로 한 PDF에 담아 내려받는다(카드 1장당 1페이지, 행사 준비용).")
    @GetMapping("/admin/tables/qr.pdf")
    public ResponseEntity<byte[]> downloadAllQr(@RequestHeader("Authorization") String authorization) {
        return toResponse(tableQrService.downloadAll(authorization));
    }

    private ResponseEntity<byte[]> toResponse(QrFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content());
    }

    /** O5 QR 재발급 (Must) — 기존 토큰 즉시 폐기, 활성 세션은 유지 */
    @Operation(summary = "O5 QR 재발급", description = "테이블의 tableToken을 새로 발급해 기존 QR을 즉시 폐기한다. 활성 세션은 유지된다.")
    @PostMapping("/admin/tables/{tableId}/regenerate-token")
    public TableAdminResponse regenerateToken(@RequestHeader("Authorization") String authorization,
                                               @PathVariable Long tableId) {
        return tableAdminService.regenerateToken(authorization, tableId);
    }

    /**
     * 테이블 1개 자동 채번 등록 (명세서 밖, 프론트 "테이블 추가" 버튼 전용) — O2와 달리 라벨을 클라이언트가
     * 정하지 않고 서버가 활성 테이블의 마지막 번호 다음으로 채번한다("T-1", "T-2"...). 삭제한 번호는 다시 쓰인다 —
     * 이용 이력이 있어 soft delete된 같은 번호 행이 있으면 그 행(같은 QR)을 되살린다.
     */
    @Operation(summary = "테이블 1개 자동 추가", description = "활성 테이블의 마지막 번호 다음(T-N)으로 채번해 테이블 1개를 등록한다. "
            + "같은 번호의 삭제된 테이블이 남아 있으면 그 테이블을 같은 QR로 되살린다.")
    @PostMapping("/admin/tables")
    @ResponseStatus(HttpStatus.CREATED)
    public TableAdminResponse addSingleTable(@RequestHeader("Authorization") String authorization) {
        return tableAdminService.addSingleTable(authorization);
    }

    /**
     * 테이블 삭제 (명세서 밖) — 마지막 번호의 테이블만 삭제 가능(그 외 409). 사용 중(활성 세션 있음)이면 409.
     * 이용 이력이 없으면 완전 삭제(QR 포함)하고, 이력이 있으면 soft delete만 한다. 어느 쪽이든 번호는 반납돼
     * 다음 추가 때 같은 번호가 다시 나온다.
     */
    @Operation(summary = "테이블 삭제", description = "마지막 번호의 테이블만 삭제할 수 있다. 이용 이력이 없으면 완전 삭제(QR 폐기), "
            + "이력이 있으면 soft delete만 한다. 어느 쪽이든 번호는 반납된다. 사용 중인 테이블은 409로 거부한다.")
    @DeleteMapping("/admin/tables/{tableId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTable(@RequestHeader("Authorization") String authorization, @PathVariable Long tableId) {
        tableAdminService.deleteTable(authorization, tableId);
    }

    /** O22 테이블 배치 좌표 저장 (Should) — 응답은 O3 항목과 같은 형태 */
    @Operation(summary = "O22 테이블 배치 좌표 저장",
            description = "운영자 배치도에서 끌어다 놓은 테이블 위치(px, 캔버스 좌상단 원점)를 저장한다.")
    @PatchMapping("/admin/tables/{tableId}/position")
    public TableStatusResponse updatePosition(@RequestHeader("Authorization") String authorization,
                                               @PathVariable Long tableId,
                                               @Valid @RequestBody TablePositionRequest request) {
        return tableAdminService.updatePosition(authorization, tableId, request);
    }

    /**
     * O22b 테이블 그리드 좌표 저장 (명세서 밖, 파일럿 전용) — 운영자가 숫자로 직접 입력하는 행/열.
     * O22(px 드래그)와는 별개 필드/엔드포인트다.
     */
    @Operation(summary = "O22b 테이블 그리드 좌표 저장",
            description = "운영자가 직접 입력한 테이블의 행/열 번호를 저장한다. row·col을 모두 비우면 미배치로 되돌린다.")
    @PatchMapping("/admin/tables/{tableId}/grid-position")
    public TableStatusResponse updateGridPosition(@RequestHeader("Authorization") String authorization,
                                                   @PathVariable Long tableId,
                                                   @RequestBody TableGridPositionRequest request) {
        return tableAdminService.updateGridPosition(authorization, tableId, request);
    }

    /**
     * O6 퇴실·초기화 (Should) — 세션 종료+테이블 비움, 멱등(세션이 없어도 200으로 EMPTY), 미결제 있어도 warning만.
     * requireSettled=true면 미결제가 남은 경우 409 CHECKOUT_UNPAID_REMAINS로 전체 롤백한다("결제 완료" 버튼용, TableAdminService 주석)
     */
    @Operation(summary = "O6 퇴실·초기화", description = "열린 세션을 종료하고 테이블을 빈 자리로 되돌린다. 세션이 없어도 200으로 EMPTY를 돌려준다(멱등). "
            + "미결제 주문이 있어도 막지 않고 unpaidWarning·warning만 준다. "
            + "requireSettled=true면 종료할 세션에 미결제가 남아 있을 때 409 CHECKOUT_UNPAID_REMAINS(details.unpaidOrderCount)로 거절하고 아무것도 바꾸지 않는다.")
    @PostMapping("/admin/tables/{tableId}/checkout")
    public TableCheckoutResponse checkout(@RequestHeader("Authorization") String authorization,
                                           @PathVariable Long tableId,
                                           @Parameter(description = "true면 미결제가 남은 퇴실을 409로 거절한다(\"결제 완료\" 버튼). 기본 false(\"테이블 비우기\")")
                                           @RequestParam(defaultValue = "false") boolean requireSettled) {
        return tableAdminService.checkoutTable(authorization, tableId, requireSettled);
    }
}
