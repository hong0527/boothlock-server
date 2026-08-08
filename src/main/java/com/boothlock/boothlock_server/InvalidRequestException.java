package com.boothlock.boothlock_server;

/** 400 INVALID_REQUEST — 요청 자체가 잘못됨 (필드 누락·형식·범위) */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
