package com.boothlock.boothlock_server.booth.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.dto.BoothInfoDto;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.error.UnauthorizedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;

@Service
public class BoothInfoService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final BoothJwtProvider jwtProvider;
    private final StaffAccountRepository staffAccountRepository;
    private final BoothRepository boothRepository;

    public BoothInfoService(
            BoothJwtProvider jwtProvider,
            StaffAccountRepository staffAccountRepository,
            BoothRepository boothRepository) {
        this.jwtProvider = jwtProvider;
        this.staffAccountRepository = staffAccountRepository;
        this.boothRepository = boothRepository;
    }

    @Transactional(readOnly = true)
    public BoothInfoDto.Response getBooth(String authorization) {
        Jwt jwt = jwtProvider.verify(authorization);
        StaffAccountEntity staff = authenticate(jwt);
        BoothEntity staffBooth = staff.getBooth();
        if (staffBooth == null) {
            throw new ForbiddenException();
        }

        Long boothId = numberClaim(jwt, "boothId");
        if (boothId == null || !boothId.equals(staffBooth.getId())) {
            throw new UnauthorizedException();
        }

        BoothEntity booth = boothRepository.findById(boothId)
                .orElseThrow(() -> new NotFoundException("부스를 찾을 수 없습니다."));
        long tableCount = boothRepository.countTablesByBoothId(boothId);
        return new BoothInfoDto.Response(
                booth.getName(),
                booth.getBankAccount(),
                booth.getOperatingHours(),
                tableCount,
                booth.isOpen());
    }

    public StaffAccountEntity authenticate(Jwt jwt) {
        Long staffId = numberClaim(jwt, "staffId");
        if (staffId == null || !staffId.toString().equals(jwt.getSubject())) {
            throw new UnauthorizedException();
        }

        StaffAccountEntity staff = staffAccountRepository.findById(staffId)
                .orElseThrow(UnauthorizedException::new);
        Long pwdAt = numberClaim(jwt, "pwdAt");
        long currentPwdAt = staff.getPasswordChangedAt().atZone(KST).toEpochSecond();
        if (!staff.isActive() || pwdAt == null || pwdAt != currentPwdAt
                || !staff.getRole().name().equals(jwt.getClaimAsString("role"))) {
            throw new UnauthorizedException();
        }
        if (staff.getRole() != StaffRole.ADMIN && staff.getRole() != StaffRole.STAFF) {
            throw new ForbiddenException();
        }
        return staff;
    }

    private Long numberClaim(Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        return value instanceof Number number ? number.longValue() : null;
    }
}
