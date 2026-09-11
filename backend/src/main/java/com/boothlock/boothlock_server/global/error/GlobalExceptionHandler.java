package com.boothlock.boothlock_server.global.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * 전역 예외 처리 — 전 파트 공용 그물.
 * 명세서 §1.4의 에러 코드 전부에 대응하는 예외 클래스가 준비되어 있다 — 던지기만 하면 된다.
 * 구현 중 처리 안 된 스프링 예외가 500으로 떨어지는 걸 발견하면 여기에 핸들러를 추가한다.
 * 이 파일을 수정하는 PR은 팀 채팅에 사전 공지 (CONTRIBUTING 6번).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    // ── 501 · 500 ────────────────────────────────────────

    /** 스켈레톤 스텁(미구현 API)의 응답 — 구현되면 자연히 사라짐.
     *  전용 예외를 쓰는 이유: UnsupportedOperationException은 불변 리스트 수정 같은
     *  실제 버그에서도 나오므로, 그걸 "미구현"으로 위장시키지 않기 위해서다 (그 경우는 500이 맞다) */
    @ExceptionHandler(NotImplementedException.class)
    public ResponseEntity<ErrorResponse> handleNotImplemented(NotImplementedException e) {
        return body(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED",
                "아직 구현되지 않은 API입니다. " + e.getMessage(), null);
    }

    /** 최후 안전망 — 예상 못 한 예외. 원인은 서버 콘솔에만 기록 (내부정보 비노출) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        e.printStackTrace();  // TODO(공통): 명세서 9.2 — 디스코드 웹훅 통보로 교체
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 오류가 발생했습니다.", null);
    }

    private ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message, Object details) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(new ErrorResponse.ErrorBody(code, message, details)));
    }
}
