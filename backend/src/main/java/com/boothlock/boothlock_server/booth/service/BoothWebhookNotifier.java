package com.boothlock.boothlock_server.booth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * 계좌 변경 통보 (명세서 O17 "커밋 후 최소정보 웹훅 전송").
 * TODO(공통): 명세서 9.2가 "계좌 변경 알림(O17)도 같은 웹훅 재사용"으로 정하고 있다.
 *             9.2(전역 에러 핸들러 웹훅) 구현 시 이 클래스를 global 쪽 공용 통보기로 합칠 것.
 */
@Component
public class BoothWebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(BoothWebhookNotifier.class);

    // 호출마다 새로 만들면 커넥션 풀·셀렉터 스레드가 매번 생성된다 — 한 번만 만들어 재사용
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private final HttpClient httpClient;
    private final Supplier<String> webhookUrlSupplier;

    public BoothWebhookNotifier() {
        this(HTTP_CLIENT, () -> System.getenv("BOOTLOCK_OPERATIONS_WEBHOOK_URL"));
    }

    BoothWebhookNotifier(HttpClient httpClient, Supplier<String> webhookUrlSupplier) {
        this.httpClient = httpClient;
        this.webhookUrlSupplier = webhookUrlSupplier;
    }

    public void notifyBankAccountChanged(Long boothId, String changedBy, LocalDateTime changedAt) {
        String url = webhookUrlSupplier.get();
        if (url == null || url.isBlank()) return;

        try {
            // 최소정보만 — 계좌번호 자체는 싣지 않는다 (명세서 O17)
            String body = "{\"event\":\"BOOTH_BANK_ACCOUNT_CHANGED\",\"boothId\":" + boothId
                    + ",\"changedBy\":\"" + escape(changedBy) + "\",\"changedAt\":\"" + changedAt + "\"}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, failure) -> logFailure(boothId, response, failure));
        } catch (RuntimeException failure) {
            log.warn("계좌 변경 웹훅 전송 시작 실패: boothId={}, cause={}",
                    boothId, failure.getClass().getSimpleName());
        }
    }

    private void logFailure(Long boothId, HttpResponse<Void> response, Throwable failure) {
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            log.warn("계좌 변경 웹훅 전송 실패: boothId={}, cause={}",
                    boothId, cause.getClass().getSimpleName());
            return;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("계좌 변경 웹훅 비정상 응답: boothId={}, status={}", boothId, response.statusCode());
        }
    }

    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
