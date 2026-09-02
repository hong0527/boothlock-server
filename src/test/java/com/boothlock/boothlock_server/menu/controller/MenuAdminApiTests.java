package com.boothlock.boothlock_server.menu.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.order.service.MenuLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MenuAdminApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MenuRepository menuRepository;
    @Autowired MenuLookup menuLookup;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffAccountRepository;

    private BoothEntity booth;
    private BoothEntity otherBooth;
    private String token;
    private String otherToken;

    @BeforeEach
    void setUp() throws Exception {
        menuRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();

        booth = boothRepository.save(new BoothEntity("메뉴 부스", "은행 1234", "10:00~20:00"));
        otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("correct-password");
        staffAccountRepository.save(new StaffAccountEntity(
                booth, "menu-admin", hash, LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        staffAccountRepository.save(new StaffAccountEntity(
                otherBooth, "other-admin", hash, LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        token = login("menu-admin", "correct-password");
        otherToken = login("other-admin", "correct-password");
    }

    @AfterEach
    void tearDown() {
        menuRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void createMenuReturnsNotionEnvelopeAndStartsNotSoldOut() throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "김치찌개",
                                "price", 9000,
                                "description", "돼지고기 사용",
                                "imageUrl", "https://cdn.example.com/menu/kimchi.jpg",
                                "visible", false,
                                "soldOut", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.message").value("메뉴가 등록되었습니다."))
                .andExpect(jsonPath("$.data.id").exists())
                .andReturn().getResponse().getContentAsString();

        Long menuId = objectMapper.readTree(response).get("data").get("id").asLong();
        MenuEntity saved = menuRepository.findById(menuId).orElseThrow();
        assertThat(saved.getBooth().getId()).isEqualTo(booth.getId());
        assertThat(saved.isVisible()).isFalse();
        assertThat(saved.isSoldOut()).isFalse();
    }

    @Test
    void createMenuDefaultsVisibleToTrue() throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "서비스", "price", 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long menuId = objectMapper.readTree(response).get("data").get("id").asLong();
        assertThat(menuRepository.findById(menuId).orElseThrow().isVisible()).isTrue();
    }

    @Test
    void createMenuTrimsNameBeforeSaving() throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "  순대 국밥  ", "price", 9000))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long menuId = objectMapper.readTree(response).get("data").get("id").asLong();
        assertThat(menuRepository.findById(menuId).orElseThrow().getName()).isEqualTo("순대 국밥");
    }

    @Test
    void createMenuRejectsInvalidBodyWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/admin/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "", "price", -1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("MENU_INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void createMenuRejectsDuplicateNameWithinSameBooth() throws Exception {
        menuRepository.save(new MenuEntity(booth, "김치찌개", 9000, null, null, true));
        menuRepository.save(new MenuEntity(otherBooth, "김치찌개", 8000, null, null, true));

        mockMvc.perform(post("/api/v1/admin/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "김치찌개", "price", 9000))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MENU_NAME_DUPLICATED"))
                .andExpect(jsonPath("$.errors.length()").value(0));
    }

    @Test
    void createMenuRejectsDuplicateNameAfterTrim() throws Exception {
        menuRepository.save(new MenuEntity(booth, "김치찌개", 9000, null, null, true));

        mockMvc.perform(post("/api/v1/admin/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "  김치찌개  ", "price", 9000))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MENU_NAME_DUPLICATED"));
    }

    @Test
    void createMenuChecksAuthBeforeSemanticBodyValidation() throws Exception {
        mockMvc.perform(post("/api/v1/admin/menus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "", "price", -1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void updateMenuPartiallyChangesOnlySentFields() throws Exception {
        MenuEntity menu = menuRepository.save(new MenuEntity(booth, "김치찌개", 9000, "old", "old-desc", true));

        mockMvc.perform(patch("/api/v1/admin/menus/{menuId}", menu.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "price", 10000,
                                "soldOut", true,
                                "visible", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("메뉴가 수정되었습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());

        MenuEntity updated = menuRepository.findById(menu.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("김치찌개");
        assertThat(updated.getPrice()).isEqualTo(10000);
        assertThat(updated.isSoldOut()).isTrue();
        assertThat(updated.isVisible()).isFalse();
    }

    @Test
    void updateMenuTrimsNameBeforeSaving() throws Exception {
        MenuEntity menu = menuRepository.save(new MenuEntity(booth, "김치찌개", 9000, null, null, true));

        mockMvc.perform(patch("/api/v1/admin/menus/{menuId}", menu.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "  순대 국밥  "))))
                .andExpect(status().isOk());

        assertThat(menuRepository.findById(menu.getId()).orElseThrow().getName()).isEqualTo("순대 국밥");
    }

    @Test
    void updateMenuRejectsDuplicateNameAfterTrim() throws Exception {
        menuRepository.save(new MenuEntity(booth, "김치찌개", 9000, null, null, true));
        MenuEntity menu = menuRepository.save(new MenuEntity(booth, "된장찌개", 8000, null, null, true));

        mockMvc.perform(patch("/api/v1/admin/menus/{menuId}", menu.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "  김치찌개  "))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MENU_NAME_DUPLICATED"));
    }

    @Test
    void updateMenuAllowsClearingNullableFields() throws Exception {
        MenuEntity menu = menuRepository.save(new MenuEntity(booth, "김치찌개", 9000, "old", "old-desc", true));

        mockMvc.perform(patch("/api/v1/admin/menus/{menuId}", menu.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":null,\"imageUrl\":null}"))
                .andExpect(status().isOk());

        MenuEntity updated = menuRepository.findById(menu.getId()).orElseThrow();
        assertThat(updated.getDescription()).isNull();
        assertThat(updated.getImageUrl()).isNull();
    }

    @Test
    void updateMenuRejectsNullForNonClearableFields() throws Exception {
        MenuEntity menu = menuRepository.save(new MenuEntity(booth, "김치찌개", 9000, null, null, true));

        mockMvc.perform(patch("/api/v1/admin/menus/{menuId}", menu.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":null,\"price\":null,\"visible\":null,\"soldOut\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_INVALID_INPUT"))
                .andExpect(jsonPath("$.errors.length()").value(4));
    }

    @Test
    void updateMenuRejectsEmptyPatch() throws Exception {
        MenuEntity menu = menuRepository.save(new MenuEntity(booth, "김치찌개", 9000, null, null, true));

        mockMvc.perform(patch("/api/v1/admin/menus/{menuId}", menu.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_NO_UPDATE_FIELD"));
    }

    @Test
    void updateMenuRejectsUnknownField() throws Exception {
        MenuEntity menu = menuRepository.save(new MenuEntity(booth, "김치찌개", 9000, null, null, true));

        mockMvc.perform(patch("/api/v1/admin/menus/{menuId}", menu.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("unsupported", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_INVALID_INPUT"))
                .andExpect(jsonPath("$.errors[0].field").value("unsupported"));
    }

    @Test
    void updateMenuRejectsInvalidField() throws Exception {
        MenuEntity menu = menuRepository.save(new MenuEntity(booth, "김치찌개", 9000, null, null, true));

        mockMvc.perform(patch("/api/v1/admin/menus/{menuId}", menu.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("price", -1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_INVALID_INPUT"))
                .andExpect(jsonPath("$.errors[0].field").value("price"));
    }

    @Test
    void updateMenuNotFound() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/menus/{menuId}", 999999L)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("visible", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));
    }

    @Test
    void updateMenuForbiddenForOtherBooth() throws Exception {
        MenuEntity menu = menuRepository.save(new MenuEntity(otherBooth, "타코", 7000, null, null, true));

        mockMvc.perform(patch("/api/v1/admin/menus/{menuId}", menu.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("visible", false))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MENU_FORBIDDEN"));
    }

    @Test
    void menuLookupReturnsSameBoothMenusAndExcludesOtherBoothMenus() {
        MenuEntity visible = menuRepository.save(new MenuEntity(booth, "김치찌개", 9000, null, null, true));
        MenuEntity hidden = menuRepository.save(new MenuEntity(booth, "숨김 메뉴", 8000, null, null, false));
        MenuEntity soldOutMenu = new MenuEntity(booth, "품절 메뉴", 7000, null, null, true);
        soldOutMenu.updateSoldOut(true);
        Long soldOutMenuId = menuRepository.save(soldOutMenu).getId();
        MenuEntity other = menuRepository.save(new MenuEntity(otherBooth, "다른 메뉴", 6000, null, null, true));

        List<MenuLookup.MenuInfo> menus = menuLookup.findByBoothIdAndMenuIds(
                booth.getId(), List.of(visible.getId(), hidden.getId(), soldOutMenuId, other.getId(), 999999L));

        assertThat(menus).extracting(MenuLookup.MenuInfo::menuId)
                .containsExactlyInAnyOrder(visible.getId(), hidden.getId(), soldOutMenuId);
        assertThat(menus).filteredOn(menu -> menu.menuId().equals(visible.getId()))
                .singleElement()
                .satisfies(menu -> {
                    assertThat(menu.name()).isEqualTo("김치찌개");
                    assertThat(menu.price()).isEqualTo(9000);
                    assertThat(menu.visible()).isTrue();
                    assertThat(menu.soldOut()).isFalse();
                });
        assertThat(menus).filteredOn(menu -> menu.menuId().equals(hidden.getId()))
                .singleElement()
                .satisfies(menu -> assertThat(menu.visible()).isFalse());
        assertThat(menus).filteredOn(menu -> menu.menuId().equals(soldOutMenuId))
                .singleElement()
                .satisfies(menu -> assertThat(menu.soldOut()).isTrue());
    }

    private String login(String loginId, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Credentials(loginId, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private record Credentials(String loginId, String password) {
    }
}
