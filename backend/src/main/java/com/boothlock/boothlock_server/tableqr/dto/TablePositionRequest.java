package com.boothlock.boothlock_server.tableqr.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * O22 배치 좌표 저장 요청 (명세서 O22) — 둘 다 필수, 0~10000.
 * 필드를 Integer가 아니라 Object로 받는 이유: Jackson 기본 설정은 "120"(문자열)을 정수로 조용히 바꿔 받고 120.7(소수)은 120으로 잘라 받는다.
 * 원래 JSON 타입을 보존해 받고 검증은 서비스가 한다 — 숫자면 반올림해 수용(운영자 프론트가 드래그 좌표를 반올림하지 않고 보낸다),
 * 문자열·불리언·배열·null은 400 (TableAdminService.validatePosition).
 */
public record TablePositionRequest(
        @Schema(type = "number", minimum = "0", maximum = "10000", example = "120", description = "px. 소수는 반올림해 저장") Object posX,
        @Schema(type = "number", minimum = "0", maximum = "10000", example = "240", description = "px. 소수는 반올림해 저장") Object posY) {
}
