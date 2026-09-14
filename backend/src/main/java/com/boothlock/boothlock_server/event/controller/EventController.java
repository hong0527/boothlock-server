package com.boothlock.boothlock_server.event.controller;

import com.boothlock.boothlock_server.event.dto.BoothListResponse;
import com.boothlock.boothlock_server.event.dto.EventMapResponse;
import com.boothlock.boothlock_server.event.service.EventQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.*;

/**
 * [담당: 홍화수] 손님 홈 화면 — API 명세서 E1·E2
 * **인증 없는 공개 축**이다 (§1.2). 축제장 입구·포스터의 대표 QR을 찍으면 열리는 화면이 쓴다.
 * 대표 QR은 토큰 없는 단순 링크라 서버가 발급·검증할 것이 없다.
 * 노출 범위가 곧 명세다 — 계좌·매출·주문·토큰은 응답에 절대 넣지 않는다 (§7-18).
 */
@Tag(name = "홈 화면 (공개)", description = "대표 QR로 들어오는 손님 홈 화면 — 부스 위치와 자리 현황 (명세서 E1·E2, 담당: 홍화수)")
@RestController
@RequestMapping("/api/v1/event")
public class EventController {

    private final EventQueryService eventQueryService;

    public EventController(EventQueryService eventQueryService) {
        this.eventQueryService = eventQueryService;
    }

    /** E1 부스 목록·좌석 현황 (Must) — 홈 화면 위쪽의 '자리 현황 보기'. 폴링 10~15초 */
    @Operation(summary = "E1 부스 목록·좌석 현황",
            description = "행사 내 부스와 부스별 테이블 현황을 반환한다. 인증이 필요 없다. 폴링 주기 10~15초 권장.")
    @GetMapping("/booths")
    public BoothListResponse getBooths(
            @Parameter(description = "카테고리 필터 (생략 시 전체)")
            @RequestParam(required = false) String category) {
        return eventQueryService.getBooths(category);
    }

    /** E2 행사 약도 (Must) — 홈 화면의 메인. 이 그림 위에 부스 핀을 찍는다 */
    @Operation(summary = "E2 행사 약도",
            description = "약도 이미지 주소와 원본 크기를 반환한다. 부스 핀 좌표는 E1의 mapX·mapY를 쓴다.")
    @GetMapping("/map")
    public EventMapResponse getMap() {
        return eventQueryService.getMap();
    }
}
