package com.boothlock.boothlock_server.menu.exception;

import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.UnauthorizedException;
import com.boothlock.boothlock_server.menu.controller.MenuController;
import com.boothlock.boothlock_server.menu.dto.MenuErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice(assignableTypes = MenuController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MenuControllerAdvice {

    @ExceptionHandler(MenuApiException.class)
    public ResponseEntity<MenuErrorResponse> handleMenu(MenuApiException exception) {
        return body(exception.getStatus(), exception.getCode(), exception.getMessage(), exception.getErrors());
    }

    @ExceptionHandler({UnauthorizedException.class, MissingRequestHeaderException.class})
    public ResponseEntity<MenuErrorResponse> handleUnauthorized(Exception exception) {
        return body(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "인증 토큰이 유효하지 않습니다.", List.of());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<MenuErrorResponse> handleForbidden(ForbiddenException exception) {
        return body(HttpStatus.FORBIDDEN, "MENU_FORBIDDEN", "해당 매장에 대한 권한이 없습니다.", List.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<MenuErrorResponse> handleInvalidRequest(Exception exception) {
        return body(HttpStatus.BAD_REQUEST, "MENU_INVALID_INPUT", "입력값이 올바르지 않습니다.", List.of());
    }

    private ResponseEntity<MenuErrorResponse> body(
            HttpStatus status, String code, String message, List<MenuErrorResponse.FieldError> errors) {
        return ResponseEntity.status(status)
                .body(new MenuErrorResponse(status.value(), code, message, errors));
    }
}
