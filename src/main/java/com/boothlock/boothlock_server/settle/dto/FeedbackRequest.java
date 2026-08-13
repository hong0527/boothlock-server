package com.boothlock.boothlock_server.settle.dto;

public record FeedbackRequest(
        Integer rating,
        Boolean easySetup,
        Boolean easyOrders,
        Boolean wouldReuse,
        String comment
) {
}