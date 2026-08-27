package com.boothlock.boothlock_server.booth.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.dto.LoginDto;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.LoginFailedException;
import com.boothlock.boothlock_server.global.error.LoginLockedException;

import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class BoothAuthService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StaffAccountRepository staffAccountRepository;
    private final BoothJwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    public BoothAuthService(StaffAccountRepository staffAccountRepository, BoothJwtProvider jwtProvider) {
        this.staffAccountRepository = staffAccountRepository;
        this.jwtProvider = jwtProvider;
    }

    @Transactional(noRollbackFor = {LoginFailedException.class, LoginLockedException.class})
    public LoginDto.Response login(LoginDto.Request request) {
        validate(request);

        StaffAccountEntity staff = staffAccountRepository.findByLoginIdForUpdate(request.loginId())
                .orElseThrow(LoginFailedException::new);
        if (!staff.isActive()) {
            throw new LoginFailedException();
        }

        LocalDateTime now = LocalDateTime.now(KST);
        if (staff.isLockedAt(now)) {
            long retryAfter = Math.max(1, java.time.Duration.between(now, staff.getLockedUntil()).toSeconds() + 1);
            throw new LoginLockedException(retryAfter);
        }
        if (!passwordEncoder.matches(request.password(), staff.getPasswordHash())) {
            long lockSeconds = staff.recordLoginFailure(now);
            if (lockSeconds > 0) {
                throw new LoginLockedException(lockSeconds);
            }
            throw new LoginFailedException();
        }

        staff.resetLoginFailures();
        String token = jwtProvider.issue(staff, Instant.now());
        BoothEntity booth = staff.getBooth();
        LoginDto.Staff responseStaff = new LoginDto.Staff(
                staff.getRole(), booth == null ? null : booth.getId(), booth == null ? null : booth.getName());
        return new LoginDto.Response(token, BoothJwtProvider.EXPIRES_IN_SECONDS, responseStaff);
    }

    private void validate(LoginDto.Request request) {
        if (request == null || request.loginId() == null || request.loginId().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new InvalidRequestException("loginId와 password는 필수입니다.");
        }
    }
}
