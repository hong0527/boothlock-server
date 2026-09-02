package com.boothlock.boothlock_server.menu.dto;

import java.util.List;

public record MenuErrorResponse(int status, String code, String message, List<FieldError> errors) {

    public record FieldError(String field, String message) {
    }
}
