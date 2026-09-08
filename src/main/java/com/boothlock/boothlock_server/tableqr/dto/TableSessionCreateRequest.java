package com.boothlock.boothlock_server.tableqr.dto;

import jakarta.validation.constraints.NotBlank;

/** C1 세션 발급 요청 — QR에 담긴 테이블 토큰 (명세서 C1) */
public record TableSessionCreateRequest(@NotBlank String tableToken) {
}
