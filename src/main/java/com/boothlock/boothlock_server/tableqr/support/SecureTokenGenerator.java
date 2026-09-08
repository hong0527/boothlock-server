package com.boothlock.boothlock_server.tableqr.support;

import java.security.SecureRandom;
import java.util.Base64;

/** tableToken(O2·O5)·sessionToken(C1) 공용 — CSPRNG 128bit 이상, URL-safe (DB스키마 §1) */
public final class SecureTokenGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256bit — VARCHAR(64) 안에 여유 있게 들어간다(43자)

    private SecureTokenGenerator() {
    }

    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
