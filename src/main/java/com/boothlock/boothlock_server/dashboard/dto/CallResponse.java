package com.boothlock.boothlock_server.dashboard.dto;

import com.boothlock.boothlock_server.dashboard.domain.CallReason;

import java.time.OffsetDateTime;

/** C6 직원 호출 생성 응답 (명세서 C6) */
public record CallResponse(Long callId, CallReason reason, OffsetDateTime createdAt) {
}
