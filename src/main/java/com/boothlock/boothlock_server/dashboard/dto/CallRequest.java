package com.boothlock.boothlock_server.dashboard.dto;

import com.boothlock.boothlock_server.dashboard.domain.CallReason;
import jakarta.validation.constraints.NotNull;

/** C6 직원 호출 생성 요청 (명세서 C6) */
public record CallRequest(@NotNull CallReason reason) {
}
