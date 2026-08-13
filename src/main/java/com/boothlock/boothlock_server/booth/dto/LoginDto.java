package com.boothlock.boothlock_server.booth.dto;

import com.boothlock.boothlock_server.booth.domain.StaffRole;

public final class LoginDto {

    private LoginDto() {
    }

    public record Request(String loginId, String password) {
    }

    public record Response(String accessToken, long expiresIn, Staff staff) {
    }

    public record Staff(StaffRole role, Long boothId, String boothName) {
    }
}
