package com.boothlock.boothlock_server.dashboard.dto;

import com.boothlock.boothlock_server.order.domain.PaymentMethod;

import jakarta.validation.constraints.NotNull;

/** O11 입금 확인 요청 (명세서 O11) */
public record PaymentConfirmRequest(@NotNull PaymentMethod method) {
}
