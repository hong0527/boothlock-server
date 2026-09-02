package com.boothlock.boothlock_server.menu.dto;

public record MenuApiResponse<T>(int status, String message, T data) {
}
