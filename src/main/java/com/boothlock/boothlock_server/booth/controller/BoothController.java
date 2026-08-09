package com.boothlock.boothlock_server.booth.controller;

import com.boothlock.boothlock_server.global.error.NotImplementedException;

import org.springframework.web.bind.annotation.*;

/**
 * [담당: 황대겸] 계정·부스 설정 — API 명세서 O1·O16·O17 (+/super/*는 파일럿 DB 시딩 대체)
 * 핵심 규칙: JWT(12h, 클레임 staffId·boothId·role), 로그인 실패 지수 백오프,
 * 계좌 변경은 ADMIN 전용 + 감사 기록 + 웹훅.
 */
@RestController
@RequestMapping("/api/v1")
public class BoothController {

    /** O1 운영진 로그인 (Must) — JWT 발급. 실패 5회 백오프 잠금, 남은 횟수 미노출 */
    @PostMapping("/admin/auth/login")
    public Object login() {
        // TODO(황대겸): 명세서 O1 — bcrypt, 401 LOGIN_FAILED / 429 LOGIN_LOCKED
        throw new NotImplementedException("O1 로그인");
    }

    /** O16 부스 정보 조회 (Must) — 부스명·계좌·운영시간·접수 스위치·테이블 수 */
    @GetMapping("/admin/booth")
    public Object getBooth() {
        // TODO(황대겸): 명세서 O16
        throw new NotImplementedException("O16 부스 조회");
    }

    /** O17 부스 설정 변경 (Must) — isOpen은 STAFF 가능, bankAccount만 ADMIN+감사 로그+웹훅 */
    @PatchMapping("/admin/booth")
    public Object updateBooth() {
        // TODO(황대겸): 명세서 O17
        throw new NotImplementedException("O17 부스 설정");
    }
}
