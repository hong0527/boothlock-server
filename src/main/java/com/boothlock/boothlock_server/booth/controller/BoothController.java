package com.boothlock.boothlock_server.booth.controller;

import com.boothlock.boothlock_server.booth.dto.BoothInfoDto;
import com.boothlock.boothlock_server.booth.dto.LoginDto;
import com.boothlock.boothlock_server.booth.service.BoothAuthService;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothSettingsService;
import com.boothlock.boothlock_server.global.error.NotImplementedException;

import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/**
 * [담당: 황대겸] 계정·부스 설정 — API 명세서 O1·O16·O17 (+/super/*는 파일럿 DB 시딩 대체)
 * 핵심 규칙: JWT(12h, 클레임 staffId·boothId·role), 로그인 실패 지수 백오프,
 * 계좌 변경은 ADMIN 전용 + 감사 기록 + 웹훅.
 */
@RestController
@RequestMapping("/api/v1")
public class BoothController {

    private final BoothAuthService boothAuthService;
    private final BoothInfoService boothInfoService;
    private final BoothSettingsService boothSettingsService;

    public BoothController(BoothAuthService boothAuthService, BoothInfoService boothInfoService,
            BoothSettingsService boothSettingsService) {
        this.boothAuthService = boothAuthService;
        this.boothInfoService = boothInfoService;
        this.boothSettingsService = boothSettingsService;
    }

    /** O1 운영진 로그인 (Must) — JWT 발급. 실패 5회 백오프 잠금, 남은 횟수 미노출 */
    @PostMapping("/admin/auth/login")
    public LoginDto.Response login(@RequestBody LoginDto.Request request) {
        return boothAuthService.login(request);
    }

    /** O16 부스 정보 조회 (Must) — 부스명·계좌·운영시간·접수 스위치·테이블 수 */
    @GetMapping("/admin/booth")
    public BoothInfoDto.Response getBooth(@RequestHeader("Authorization") String authorization) {
        return boothInfoService.getBooth(authorization);
    }

    /** O17 부스 설정 변경 (Must) — isOpen은 STAFF 가능, bankAccount만 ADMIN+감사 로그+웹훅 */
    @PatchMapping("/admin/booth")
    public BoothInfoDto.Response updateBooth(@RequestHeader("Authorization") String authorization,
            @RequestBody JsonNode request) {
        return boothSettingsService.update(authorization, request);
    }
}
