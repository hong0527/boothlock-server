package com.boothlock.boothlock_server.settle.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        @NotNull Boolean easySetup,
        @NotNull Boolean easyOrders,
        @NotNull Boolean wouldReuse,
        @Size(max = 1000) String comment
) {
}
