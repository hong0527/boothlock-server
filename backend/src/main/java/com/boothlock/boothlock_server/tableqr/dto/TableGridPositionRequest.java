package com.boothlock.boothlock_server.tableqr.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * O22b 그리드 좌표 저장 요청 — 파일럿 전용, 운영자가 숫자로 직접 입력하는 행/열.
 * O22(posX/posY, px 드래그)와는 별개 개념이라 별도 DTO/엔드포인트로 둔다.
 * 둘 다 null이면 미배치로 되돌린다(트레이로 돌아감) — 하나만 null이면 400 (TableAdminService.validateGridPosition).
 *
 * 필드를 Integer가 아니라 Object로 받는 이유: Jackson 기본 설정은 JSON 문자열 "3"을 정수로 조용히 바꿔 받고
 * 3.5(소수)는 3으로 잘라 받는다(TablePositionRequest의 posX/posY와 같은 문제). 원래 JSON 타입을 보존해 받고
 * 검증은 서비스가 한다 — Integer가 아니면(문자열·소수·불리언·배열 등) 400 (TableAdminService.validateGridIndex).
 */
public record TableGridPositionRequest(
        @Schema(type = "integer", minimum = "1", maximum = "50", example = "3", description = "행 번호. 미배치로 되돌리려면 col과 함께 null") Object row,
        @Schema(type = "integer", minimum = "1", maximum = "50", example = "5", description = "열 번호. 미배치로 되돌리려면 row와 함께 null") Object col) {
}
