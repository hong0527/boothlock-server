package com.boothlock.boothlock_server.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** O13 운영자 취소 요청 (명세서 O13) */
public record CancelRequest(@NotBlank @Size(max = 100) String reason) {
}
