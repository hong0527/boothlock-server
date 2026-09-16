package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.UnauthorizedException;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대시보드 파트 공용 운영자 인증 — Authorization 헤더를 부스 소속 운영자로 바꾼다 (명세서 §1.2·§7-4).
 * 토큰 없음·서명 오류·비번 변경 후 토큰은 401, SUPER_ADMIN(부스 무소속)은 403.
 * 부스 스코프는 반드시 여기서 나온 값만 쓴다 — 요청 파라미터·본문의 boothId는 신뢰하지 않는다 (§7-21)
 */
@Component
public class BoothStaffAuthenticator {

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;

    public BoothStaffAuthenticator(BoothJwtProvider jwtProvider, BoothInfoService boothInfoService) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
    }

    /** 호출자 트랜잭션이 있으면 합류한다 — booth가 LAZY라 트랜잭션 밖에서 넘겨받으면 authenticateBoothId로 id를 읽을 것 */
    @Transactional(readOnly = true)
    public StaffAccountEntity authenticate(String authorization) {
        Jwt jwt = jwtProvider.verify(authorization);
        StaffAccountEntity staff = boothInfoService.authenticate(jwt);
        if (staff.getBooth() == null) {
            throw new ForbiddenException();
        }
        // 토큰 발급 뒤 계정의 소속 부스가 바뀌었으면 옛 토큰으로 새 부스에 들어가지 못하게 한다 (O16 BoothInfoService와 같은 기준)
        Object boothClaim = jwt.getClaims().get("boothId");
        if (!(boothClaim instanceof Number number) || number.longValue() != staff.getBooth().getId()) {
            throw new UnauthorizedException();
        }
        return staff;
    }

    /** 트랜잭션 없이 쓰는 호출자(O14)용 — booth id를 트랜잭션 안에서 꺼내 돌려준다 */
    @Transactional(readOnly = true)
    public Long authenticateBoothId(String authorization) {
        return authenticate(authorization).getBooth().getId();
    }
}
