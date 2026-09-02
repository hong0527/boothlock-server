package com.boothlock.boothlock_server.menu.exception;

import com.boothlock.boothlock_server.menu.dto.MenuErrorResponse;
import org.springframework.http.HttpStatus;

import java.util.List;

public class MenuApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<MenuErrorResponse.FieldError> errors;

    public MenuApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public MenuApiException(HttpStatus status, String code, String message, List<MenuErrorResponse.FieldError> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = errors;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<MenuErrorResponse.FieldError> getErrors() {
        return errors;
    }
}
