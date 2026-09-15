package com.boothlock.boothlock_server.tableqr.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** O22 테이블 배치 좌표 저장 요청 (명세서 O22) — 둘 다 필수, 0 이상 */
public record TablePositionRequest(
        @NotNull @PositiveOrZero Integer posX,
        @NotNull @PositiveOrZero Integer posY) {
}
