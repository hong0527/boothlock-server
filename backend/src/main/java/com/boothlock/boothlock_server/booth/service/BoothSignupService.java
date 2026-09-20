package com.boothlock.boothlock_server.booth.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.dto.LoginDto;
import com.boothlock.boothlock_server.booth.dto.SignupDto;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.InvalidStateException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 임시 데모 기능 — 원래 API명세서(O1)는 "회원가입 API 없음, 계정은 시더로만 생성"이라고 명시했었다
 * (사업자등록 없는 임시 부스 대상이라 신원 확인 없이 셀프 등록을 열면 남의 점포명을 사칭해 손님 결제를
 * 가로챌 수 있어서였음). 이번엔 그 결정을 뒤집기로 팀이 정했다 — 단, 신원 확인 절차를 새로 만들지는
 * 않았으므로 이 위험은 여전하다. 그래서 {@code boothlock.signup.enabled}(기본 꺼짐)로 감싸
 * 축제 운영 중에는 시더 계정만 쓰고, 시연·심사 때만 켠다 (§ PR 설명 참고).
 *
 * <p>부스와 ADMIN 계정을 한 트랜잭션에서 함께 만든다 — 계정 생성만 실패해도(예: loginId 중복) 부스만
 * 남는 고아 행이 생기지 않게. 계좌는 아직 안 받으므로 AccountPage.tsx가 인식하는 미등록 안내값을
 * 그대로 넣는다.
 */
@Service
public class BoothSignupService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MIN_PASSWORD_LENGTH = 8;
    /** frontend/src/pages/settings/AccountPage.tsx의 isUnregistered 정규식과 반드시 같은 문구여야 한다 */
    private static final String UNREGISTERED_BANK_ACCOUNT = "계좌 미입력 - 로그인 후 설정에서 등록";

    private final BoothRepository boothRepository;
    private final StaffAccountRepository staffAccountRepository;
    private final BoothJwtProvider jwtProvider;
    private final boolean signupEnabled;
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    public BoothSignupService(BoothRepository boothRepository, StaffAccountRepository staffAccountRepository,
            BoothJwtProvider jwtProvider, @Value("${boothlock.signup.enabled:false}") boolean signupEnabled) {
        this.boothRepository = boothRepository;
        this.staffAccountRepository = staffAccountRepository;
        this.jwtProvider = jwtProvider;
        this.signupEnabled = signupEnabled;
    }

    @Transactional
    public LoginDto.Response signup(SignupDto.Request request) {
        if (!signupEnabled) {
            throw new ForbiddenException("회원가입 기능이 비활성화되어 있습니다.");
        }
        String boothName = validate(request);

        BoothEntity booth = boothRepository.save(new BoothEntity(boothName, UNREGISTERED_BANK_ACCOUNT, null));

        StaffAccountEntity staff;
        try {
            staff = staffAccountRepository.save(new StaffAccountEntity(
                    booth, request.loginId().trim(), passwordEncoder.encode(request.password()),
                    LocalDateTime.now(KST), StaffRole.ADMIN));
            staffAccountRepository.flush();   // 유니크 제약 위반을 커밋 시점이 아니라 여기서 터뜨린다 (EventSeeder와 같은 이유)
        } catch (DataIntegrityViolationException e) {
            throw new InvalidStateException("이미 사용 중인 아이디입니다.");
        }

        String token = jwtProvider.issue(staff, Instant.now());
        LoginDto.Staff responseStaff = new LoginDto.Staff(staff.getRole(), booth.getId(), booth.getName());
        return new LoginDto.Response(token, BoothJwtProvider.EXPIRES_IN_SECONDS, responseStaff);
    }

    private String validate(SignupDto.Request request) {
        if (request == null) {
            throw new InvalidRequestException("요청 본문이 비어 있습니다.");
        }
        String boothName = request.boothName() == null ? "" : request.boothName().trim();
        String loginId = request.loginId() == null ? "" : request.loginId().trim();
        String password = request.password() == null ? "" : request.password();

        if (boothName.isEmpty() || boothName.length() > 50) {
            throw new InvalidRequestException("점포명은 1자 이상 50자 이하여야 합니다.");
        }
        if (loginId.isEmpty() || loginId.length() > 50) {
            throw new InvalidRequestException("아이디는 1자 이상 50자 이하여야 합니다.");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidRequestException("비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다.");
        }
        return boothName;
    }
}
