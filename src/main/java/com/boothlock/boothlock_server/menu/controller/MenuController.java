package com.boothlock.boothlock_server.menu.controller;

import com.boothlock.boothlock_server.global.error.NotImplementedException;
import com.boothlock.boothlock_server.menu.dto.MenuResponse;
import com.boothlock.boothlock_server.menu.service.MenuService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/**
 * [담당: 권희원] 메뉴 — API 명세서 C2·O7·O8·O9
 * 참고 패턴: 엔티티/JpaRepository/Repository 구조는 README "공통 개발 패턴" 참조.
 */
@RestController
@RequestMapping("/api/v1")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    /** C2 메뉴판 조회 (Must) — visible=false 제외, soldOut 표시, 잔여 수량 필드 없음(팀 확정) */
    @GetMapping("/menus")
    public Object getMenus() {
        // TODO(권희원): 명세서 C2 — 응답에 boothName, isOpen 포함
        throw new NotImplementedException("C2 메뉴판 조회");
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
    @PostMapping("/admin/uploads")
    public Object upload() {
        // TODO(권희원): 명세서 O9
        throw new NotImplementedException("O9 사진 업로드");
    }
}
