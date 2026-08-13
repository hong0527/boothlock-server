package com.boothlock.boothlock_server.booth.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;

@Component
public class BoothWebhookNotifier {
    public void notifyBankAccountChanged(Long boothId, String changedBy, LocalDateTime changedAt) {
        String url = System.getenv("BOOTLOCK_OPERATIONS_WEBHOOK_URL");
        if (url == null || url.isBlank()) return;
        String body = "{\"event\":\"BOOTH_BANK_ACCOUNT_CHANGED\",\"boothId\":" + boothId
                + ",\"changedBy\":\"" + escape(changedBy) + "\",\"changedAt\":\"" + changedAt + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ignored -> null);
    }

    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
