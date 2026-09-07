package com.boothlock.boothlock_server.booth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class BoothWebhookNotifierTests {

    private static final String WEBHOOK_URL = "https://secret.example/webhook/token";

    @Test
    @SuppressWarnings("unchecked")
    void logsNonSuccessfulHttpStatusWithoutWebhookUrl(CapturedOutput output) {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(httpClient.sendAsync(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        BoothWebhookNotifier notifier = new BoothWebhookNotifier(httpClient, () -> WEBHOOK_URL);

        notifier.notifyBankAccountChanged(1L, "admin", LocalDateTime.of(2026, 9, 7, 12, 0));

        assertThat(output).contains("계좌 변경 웹훅 비정상 응답: boothId=1, status=500")
                .doesNotContain(WEBHOOK_URL)
                .doesNotContain("admin");
    }

    @Test
    void logsAsyncFailureTypeWithoutSensitiveDetails(CapturedOutput output) {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.sendAsync(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("secret failure detail")));
        BoothWebhookNotifier notifier = new BoothWebhookNotifier(httpClient, () -> WEBHOOK_URL);

        notifier.notifyBankAccountChanged(2L, "admin", LocalDateTime.of(2026, 9, 7, 12, 0));

        assertThat(output).contains("계좌 변경 웹훅 전송 실패: boothId=2, cause=IOException")
                .doesNotContain(WEBHOOK_URL)
                .doesNotContain("secret failure detail")
                .doesNotContain("admin");
    }

    @Test
    void logsInvalidUrlWithoutExposingIt(CapturedOutput output) {
        BoothWebhookNotifier notifier = new BoothWebhookNotifier(mock(HttpClient.class), () -> "not a valid url");

        notifier.notifyBankAccountChanged(3L, "admin", LocalDateTime.of(2026, 9, 7, 12, 0));

        assertThat(output).contains("계좌 변경 웹훅 전송 시작 실패: boothId=3, cause=IllegalArgumentException")
                .doesNotContain("not a valid url")
                .doesNotContain("admin");
    }
}
