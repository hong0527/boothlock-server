package com.boothlock.boothlock_server.booth.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;

/**
 * 계좌 변경 통보 (명세서 O17 "커밋 후 최소정보 웹훅 전송").
 * TODO(공통): 명세서 9.2가 "계좌 변경 알림(O17)도 같은 웹훅 재사용"으로 정하고 있다.
 *             9.2(전역 에러 핸들러 웹훅) 구현 시 이 클래스를 global 쪽 공용 통보기로 합칠 것.
 */
@Component
public class BoothWebhookNotifier {

    // 호출마다 새로 만들면 커넥션 풀·셀렉터 스레드가 매번 생성된다 — 한 번만 만들어 재사용
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    public void notifyBankAccountChanged(Long boothId, String changedBy, LocalDateTime changedAt) {
        String url = System.getenv("BOOTLOCK_OPERATIONS_WEBHOOK_URL");
        if (url == null || url.isBlank()) return;
        // 최소정보만 — 계좌번호 자체는 싣지 않는다 (명세서 O17)
        String body = "{\"event\":\"BOOTH_BANK_ACCOUNT_CHANGED\",\"boothId\":" + boothId
                + ",\"changedBy\":\"" + escape(changedBy) + "\",\"changedAt\":\"" + changedAt + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ignored -> null);
    }

    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
