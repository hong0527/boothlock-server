package com.boothlock.boothlock_server.menu.controller;

import com.boothlock.boothlock_server.menu.dto.MenuBoardResponse;
import com.boothlock.boothlock_server.menu.dto.MenuUploadResponse;
import com.boothlock.boothlock_server.menu.dto.MenuResponse;
import com.boothlock.boothlock_server.menu.service.MenuImageUploadService;
import com.boothlock.boothlock_server.menu.service.MenuService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * [담당: 권희원] 메뉴 — API 명세서 C2·O7·O8·O9
 * 참고 패턴: 엔티티/JpaRepository/Repository 구조는 README "공통 개발 패턴" 참조.
 */
@RestController
@RequestMapping("/api/v1")
public class MenuController {

    private final MenuService menuService;
    private final MenuImageUploadService menuImageUploadService;

    public MenuController(MenuService menuService, MenuImageUploadService menuImageUploadService) {
        this.menuService = menuService;
        this.menuImageUploadService = menuImageUploadService;
    }

    /** C2 메뉴판 조회 (Must) — visible=false 제외, soldOut 표시, 잔여 수량 필드 없음(팀 확정) */
    @GetMapping("/menus")
    public MenuBoardResponse getMenus(@RequestHeader("X-Session-Token") String sessionToken) {
        return menuService.getMenuBoard(sessionToken);
    }

    /** O7 메뉴 등록 (Must) — name(1~50자)·price(0 이상)·description(알레르기 표기)·imageUrl·visible */
    @PostMapping("/admin/menus")
    public ResponseEntity<MenuResponse> createMenu(
            @RequestHeader("Authorization") String authorization,
            @RequestBody JsonNode request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(menuService.create(authorization, request));
    }

    /** O8 수정·숨김·품절 (Must) — PATCH 부분 수정, 품절 토글은 이 API 하나. DELETE 없음 */
    @PatchMapping("/admin/menus/{menuId}")
    public MenuResponse updateMenu(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long menuId,
            @RequestBody JsonNode request) {
        return menuService.update(authorization, menuId, request);
    }

    /** O9 사진 업로드 (Must) — multipart ≤5MB, 매직바이트 검증·SVG 거부·1080px 재인코딩 */
    @PostMapping(value = "/admin/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MenuUploadResponse> upload(
            @RequestHeader("Authorization") String authorization,
            @RequestPart(value = "file", required = false) MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Content-Type-Options", "nosniff")
                .body(menuImageUploadService.upload(authorization, file));
    }
}
