package com.boothlock.boothlock_server.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/** E2 행사 약도 응답 (명세서 E2) — 홈 화면의 배경 그림 */
@Schema(name = "EventMapResponse")
public record EventMapResponse(
        String imageUrl,
        int width,
        int height,
        OffsetDateTime updatedAt) {
}
