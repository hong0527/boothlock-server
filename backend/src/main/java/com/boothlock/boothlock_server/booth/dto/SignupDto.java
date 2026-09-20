package com.boothlock.boothlock_server.booth.dto;

public final class SignupDto {

    private SignupDto() {
    }

    public record Request(String boothName, String loginId, String password) {
    }
}
