package com.boothlock.boothlock_server.booth.service;

import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;

@Component
public class BoothJwtProvider {

    static final long EXPIRES_IN_SECONDS = 43_200;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** application.properties의 로컬 개발 기본값 — 이 값이 그대로 쓰이면 경고한다 */
    private static final String DEV_SECRET = "dev-only-insecure-secret-do-not-use-in-production";

    private static final Log log = LogFactory.getLog(BoothJwtProvider.class);

    private final JwtEncoder jwtEncoder;

    public BoothJwtProvider(@Value("${boothlock.jwt.secret}") String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("BOOTLOCK_JWT_SECRET은 32바이트 이상이어야 합니다.");
        }
        if (DEV_SECRET.equals(secret)) {
            log.warn("JWT 서명 키가 저장소에 공개된 개발용 기본값입니다. "
                    + "운영 배포 시 BOOTLOCK_JWT_SECRET 환경 변수로 반드시 덮어쓰세요.");
        }
        this.jwtEncoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    public String issue(StaffAccountEntity staff, Instant now) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(staff.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(EXPIRES_IN_SECONDS))
                .claim("staffId", staff.getId())
                .claim("role", staff.getRole().name())
                .claim("pwdAt", staff.getPasswordChangedAt().atZone(KST).toEpochSecond());
        if (staff.getBooth() != null) {
            claims.claim("boothId", staff.getBooth().getId());
        }

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
