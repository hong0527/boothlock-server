package com.boothlock.boothlock_server.global.error;

import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.Map;

/**
 * 전역 예외 처리 — 전 파트 공용 그물.
 * 명세서 §1.4의 에러 코드 전부에 대응하는 예외 클래스가 준비되어 있다 — 던지기만 하면 된다.
 * 구현 중 처리 안 된 스프링 예외가 500으로 떨어지는 걸 발견하면 여기에 핸들러를 추가한다.
 * 이 파일을 수정하는 PR은 팀 채팅에 사전 공지 (CONTRIBUTING 6번).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ── 400 ──────────────────────────────────────────────

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(InvalidRequestException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), null);
    }

    /** 경로변수·파라미터 타입 오류 (예: orderId 자리에 문자열) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값의 형식이 올바르지 않습니다.", null);
    }

    /** 본문 JSON 파싱 실패 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 본문(JSON) 형식이 올바르지 않습니다.", null);
    }

    /** @Valid 검증 실패 (예: 메뉴명 1~50자, 취소 사유 1~100자) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("요청 값이 검증에 실패했습니다.");
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", msg, null);
    }

    /** 필수 헤더 누락 — 인증 토큰이면 401(§1.4), 그 외(Idempotency-Key 등)는 400 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        String name = e.getHeaderName();
        if ("X-Session-Token".equalsIgnoreCase(name) || "Authorization".equalsIgnoreCase(name)) {
            return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.", null);
        }
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "필수 헤더가 없습니다: " + name, null);
    }

    /** 필수 쿼리 파라미터 누락 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "필수 파라미터가 없습니다: " + e.getParameterName(), null);
    }

    /** 업로드 용량 초과 (명세서 O9: 최대 5MB — application.properties 상한과 한 세트) */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "파일이 너무 큽니다. 최대 5MB까지 업로드할 수 있습니다.", null);
    }

    // ── 401 · 403 ────────────────────────────────────────

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException e) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage(), null);
    }

    @ExceptionHandler(LoginFailedException.class)
    public ResponseEntity<ErrorResponse> handleLoginFailed(LoginFailedException e) {
        return body(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", e.getMessage(), null);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage(), null);
    }

    // ── 404 · 405 ────────────────────────────────────────

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), null);
    }

    /** 존재하지 않는 경로 호출 (오타 등) */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoRoute(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 경로가 없습니다.", null);
    }

    /** 있는 경로에 잘못된 메서드 (§1.4 외 보조 코드 — 표준 HTTP 의미 유지) */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleBadMethod(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다.", null);
    }

    /** Content-Type 불일치 (§1.4 외 보조 코드) */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleBadMediaType(HttpMediaTypeNotSupportedException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type이 올바르지 않습니다. application/json을 사용하세요.", null);
    }

    // ── 409 · 410 ────────────────────────────────────────

    @ExceptionHandler(SoldOutException.class)
    public ResponseEntity<ErrorResponse> handleSoldOut(SoldOutException e) {
        return body(HttpStatus.CONFLICT, "SOLD_OUT", e.getMessage(), e.getSoldOutMenus());
    }

    @ExceptionHandler(InvalidStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(InvalidStateException e) {
        return body(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage(), null);
    }

    @ExceptionHandler(OrderClosedException.class)
    public ResponseEntity<ErrorResponse> handleOrderClosed(OrderClosedException e) {
        return body(HttpStatus.CONFLICT, "ORDER_CLOSED", e.getMessage(), null);
    }

    @ExceptionHandler(PartySizeRequiredException.class)
    public ResponseEntity<ErrorResponse> handlePartySizeRequired(PartySizeRequiredException e) {
        return body(HttpStatus.CONFLICT, "PARTY_SIZE_REQUIRED", e.getMessage(), null);
    }

    @ExceptionHandler(CheckoutUnpaidRemainsException.class)
    public ResponseEntity<ErrorResponse> handleCheckoutUnpaidRemains(CheckoutUnpaidRemainsException e) {
        return body(HttpStatus.CONFLICT, "CHECKOUT_UNPAID_REMAINS", e.getMessage(),
                Map.of("unpaidOrderCount", e.getUnpaidOrderCount()));
    }

    @ExceptionHandler(CheckoutPendingApprovalException.class)
    public ResponseEntity<ErrorResponse> handleCheckoutPendingApproval(CheckoutPendingApprovalException e) {
        return body(HttpStatus.CONFLICT, "CHECKOUT_PENDING_APPROVAL", e.getMessage(),
                Map.of("pendingOrderCount", e.getPendingOrderCount()));
    }

    @ExceptionHandler(AlreadyPaidException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyPaid(AlreadyPaidException e) {
        return body(HttpStatus.CONFLICT, "ALREADY_PAID", e.getMessage(), null);
    }

    @ExceptionHandler(SessionExpiredException.class)
    public ResponseEntity<ErrorResponse> handleSessionExpired(SessionExpiredException e) {
        return body(HttpStatus.GONE, "SESSION_EXPIRED", e.getMessage(), null);
    }

    // ── 429 ──────────────────────────────────────────────

    @ExceptionHandler(CallCooldownException.class)
    public ResponseEntity<ErrorResponse> handleCallCooldown(CallCooldownException e) {
        return body(HttpStatus.TOO_MANY_REQUESTS, "CALL_COOLDOWN", e.getMessage(),
                Map.of("retryAfterSeconds", e.getRetryAfterSeconds()));
    }

    @ExceptionHandler(OrderRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleRateLimited(OrderRateLimitedException e) {
        return body(HttpStatus.TOO_MANY_REQUESTS, "ORDER_RATE_LIMITED", e.getMessage(), null);
    }

    @ExceptionHandler(LoginLockedException.class)
    public ResponseEntity<ErrorResponse> handleLoginLocked(LoginLockedException e) {
        return body(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_LOCKED", e.getMessage(),
                Map.of("retryAfterSeconds", e.getRetryAfterSeconds()));
    }

    // ── 503 ──────────────────────────────────────────────

    @ExceptionHandler(UploadBusyException.class)
    public ResponseEntity<ErrorResponse> handleUploadBusy(UploadBusyException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_BUSY", e.getMessage(), null);
    }

    /**
     * DB가 잠깐 못 받는 상황 — 버그가 아니라 부하·잠금 경합이라 500 대신 503으로 "다시 시도"를 알린다.
     * <ul>
     *   <li>PessimisticLockingFailureException(하위 CannotAcquireLockException 포함): MySQL 1205 잠금 대기 초과
     *       (application-rds의 innodb_lock_wait_timeout=20초)·1213 데드락 희생. 문장·트랜잭션은 이미 되돌려져 재시도가 안전하다</li>
     *   <li>CannotCreateTransactionException: 트랜잭션 시작 시 커넥션을 못 얻음 — Hikari connection-timeout(10초) 초과가 여기로 온다</li>
     *   <li>DataAccessResourceFailureException: 커넥션이 끊김(RDS 장애 조치·socketTimeout 30초)</li>
     *   <li>QueryTimeoutException: 문장 제한 시간 초과</li>
     * </ul>
     * 한 줄 warn만 남긴다 — 잠금 경합이 몰릴 때 요청마다 스택트레이스를 찍으면 로그가 원인보다 커진다. 몇 초 안에 수십 줄이
     * 쌓이면 그 자체가 신호다(풀 10개 포화·긴 트랜잭션). 멱등키가 있는 C3·O14는 같은 키로 다시 보내면 중복 없이 이어진다
     */
    @ExceptionHandler({PessimisticLockingFailureException.class, CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class, QueryTimeoutException.class})
    public ResponseEntity<ErrorResponse> handleTemporarilyUnavailable(Exception e) {
        log.warn("DB 일시 불가로 503 응답: {} — {}", e.getClass().getSimpleName(), rootMessage(e));
        return body(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "잠시 후 다시 시도해주세요.", null);
    }

    // ── 클라이언트가 먼저 끊음 ─────────────────────────────

    /**
     * 손님 폰이 응답을 받기 전에 연결을 끊었다(화면 이탈·약한 축제장 망·프론트 요청 제한시간). 서버 잘못이 아니고, 응답을 쓸 곳도
     * 없다 — 여기서 JSON 본문을 쓰려 하면 같은 끊긴 소켓에 또 쓰다 예외가 겹친다. debug로만 남기고 본문 없이 끝낸다.
     * 예전에는 아래 500 안전망으로 떨어져 요청마다 스택트레이스가 찍혀 진짜 500을 가렸다
     */
    @ExceptionHandler({ClientAbortException.class, AsyncRequestNotUsableException.class})
    public void handleClientAbort(Exception e) {
        log.debug("클라이언트 연결 끊김: {}", rootMessage(e));
    }

    /** 컨테이너가 ClientAbortException으로 감싸지 않은 끊김(Broken pipe·Connection reset)만 같은 처리 — 그 밖의 IOException은 500 */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErrorResponse> handleIo(IOException e) {
        if (isClientDisconnect(e)) {
            log.debug("클라이언트 연결 끊김: {}", rootMessage(e));
            return null;
        }
        return handleUnknown(e);
    }

    // ── 501 · 500 ────────────────────────────────────────

    /** 스켈레톤 스텁(미구현 API)의 응답 — 구현되면 자연히 사라짐.
     *  전용 예외를 쓰는 이유: UnsupportedOperationException은 불변 리스트 수정 같은
     *  실제 버그에서도 나오므로, 그걸 "미구현"으로 위장시키지 않기 위해서다 (그 경우는 500이 맞다) */
    @ExceptionHandler(NotImplementedException.class)
    public ResponseEntity<ErrorResponse> handleNotImplemented(NotImplementedException e) {
        return body(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED",
                "아직 구현되지 않은 API입니다. " + e.getMessage(), null);
    }

    /**
     * 최후 안전망 — 예상 못 한 예외. 원인은 서버 로그에만 기록 (내부정보 비노출).
     * printStackTrace는 로거를 거치지 않아 표준에러에 시각·요청 스레드 없이 섞여 나왔다 — 로거로 스택트레이스를 한 번 남긴다
     * (명세서 9.2의 디스코드 웹훅 통보는 아직 없다)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        log.error("처리되지 않은 예외로 500 응답", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 오류가 발생했습니다.", null);
    }

    private static boolean isClientDisconnect(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (t instanceof ClientAbortException || (message != null
                    && (message.contains("Broken pipe") || message.contains("Connection reset by peer")))) {
                return true;
            }
        }
        return false;
    }

    /** 한 줄 로그용 — 가장 안쪽 원인의 메시지(MySQL 오류 문구·Hikari 대기 시간 등이 여기 있다) */
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message, Object details) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(new ErrorResponse.ErrorBody(code, message, details)));
    }
}
