package com.boothlock.boothlock_server.booth.service;

import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.global.error.UnauthorizedException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;

@Component
public class BoothJwtProvider {

    /**
     * 20시간(72,000초) — 예전 12시간은 오전에 로그인한 스태프가 저녁 장사 한가운데서 401로 쫓겨났다(축제 부스는 준비부터 마감까지
     * 하루 12시간을 넘긴다). 하루 한 번 로그인이면 되도록 늘리되, 영업일이 바뀌면 한 번은 다시 로그인하도록 24시간 미만으로 둔다.
     * 토큰 탈취 대응은 만료가 아니라 비밀번호 재발급(pwdAt 대조로 기존 토큰 즉시 무효)이 맡는다
     */
    static final long EXPIRES_IN_SECONDS = 72_000;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** application.properties의 로컬 개발 기본값 — 이 값이 그대로 쓰이면 경고한다 */
    private static final String DEV_SECRET = "dev-only-insecure-secret-do-not-use-in-production";

    private static final Log log = LogFactory.getLog(BoothJwtProvider.class);

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;

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
        this.jwtDecoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
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

    public Jwt verify(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring(7).isBlank()) {
            throw new UnauthorizedException();
        }
        try {
            return jwtDecoder.decode(authorization.substring(7));
        } catch (JwtException exception) {
            throw new UnauthorizedException();
        }
    }
}
