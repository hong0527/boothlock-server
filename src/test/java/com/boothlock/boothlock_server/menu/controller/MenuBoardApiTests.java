package com.boothlock.boothlock_server.menu.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MenuBoardApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired MenuRepository menuRepository;
    @Autowired BoothRepository boothRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;

    private BoothEntity booth;
    private String sessionToken;

    @BeforeEach
    void setUp() {
        menuRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        boothRepository.deleteAll();

        booth = boothRepository.save(new BoothEntity("메뉴판 부스", "은행 1234", "10:00~20:00"));
        TableEntity table = tableRepository.save(new TableEntity(booth, "A-1", "table-token-1"));
        sessionToken = "session-token-1";
        tableSessionRepository.save(new TableSessionEntity(table, sessionToken, LocalDateTime.now().minusMinutes(10)));
    }

    @AfterEach
    void tearDown() {
        menuRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void returnsVisibleMenusWithBoothStatus() throws Exception {
        MenuEntity visible = menuRepository.save(new MenuEntity(
                booth, "김치찌개", 9000, "https://cdn.example.com/kimchi.jpg", "돼지고기 사용", true));
        MenuEntity soldOut = new MenuEntity(booth, "부침개", 7000, null, null, true);
        soldOut.updateSoldOut(true);
        menuRepository.save(soldOut);
        menuRepository.save(new MenuEntity(booth, "숨김 메뉴", 1000, null, null, false));

        mockMvc.perform(get("/api/v1/menus").header("X-Session-Token", sessionToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boothName").value("메뉴판 부스"))
                .andExpect(jsonPath("$.isOpen").value(true))
                .andExpect(jsonPath("$.menus.length()").value(2))
                .andExpect(jsonPath("$.menus[0].id").value(visible.getId()))
                .andExpect(jsonPath("$.menus[0].name").value("김치찌개"))
                .andExpect(jsonPath("$.menus[0].price").value(9000))
                .andExpect(jsonPath("$.menus[0].imageUrl").value("https://cdn.example.com/kimchi.jpg"))
                .andExpect(jsonPath("$.menus[0].description").value("돼지고기 사용"))
                .andExpect(jsonPath("$.menus[0].soldOut").value(false))
                .andExpect(jsonPath("$.menus[0].visible").doesNotExist())
                .andExpect(jsonPath("$.menus[1].name").value("부침개"))
                .andExpect(jsonPath("$.menus[1].soldOut").value(true));
    }

    @Test
    void returnsClosedBoothStatusWithMenus() throws Exception {
        booth.updateOpen(false);
        boothRepository.save(booth);
        menuRepository.save(new MenuEntity(booth, "김치찌개", 9000, null, null, true));

        mockMvc.perform(get("/api/v1/menus").header("X-Session-Token", sessionToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isOpen").value(false))
                .andExpect(jsonPath("$.menus.length()").value(1));
    }

    @Test
    void rejectsMissingSessionToken() throws Exception {
        mockMvc.perform(get("/api/v1/menus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsUnknownSessionTokenWithGone() throws Exception {
        // 모르는 토큰과 만료된 토큰 모두 410 — 손님이 할 일은 어느 쪽이든 QR 재스캔이라 소비자 API 전체가 같은 코드를 준다
        mockMvc.perform(get("/api/v1/menus").header("X-Session-Token", "unknown-token"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
    }

    @Test
    void doesNotExposeOtherBoothMenus() throws Exception {
        // 세션이 속한 부스의 메뉴만 보여야 한다 (명세서 §1.4 타 부스 리소스 은닉)
        menuRepository.save(new MenuEntity(booth, "내 부스 김치전", 8000, null, null, true));
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 9999", "10:00~20:00"));
        menuRepository.save(new MenuEntity(otherBooth, "남의 부스 파전", 9000, null, null, true));

        mockMvc.perform(get("/api/v1/menus").header("X-Session-Token", sessionToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boothName").value("메뉴판 부스"))
                .andExpect(jsonPath("$.menus.length()").value(1))
                .andExpect(jsonPath("$.menus[0].name").value("내 부스 김치전"));
    }

    @Test
    void menuBoardCountsAsSessionActivity() throws Exception {
        // 공용 인증 계층이 요청마다 활동 시각을 갱신한다
        java.time.LocalDateTime before = tableSessionRepository.findBySessionToken(sessionToken)
                .orElseThrow().getLastActivityAt();

        mockMvc.perform(get("/api/v1/menus").header("X-Session-Token", sessionToken))
                .andExpect(status().isOk());

        java.time.LocalDateTime after = tableSessionRepository.findBySessionToken(sessionToken)
                .orElseThrow().getLastActivityAt();
        org.junit.jupiter.api.Assertions.assertTrue(after.isAfter(before));
    }

    @Test
    void rejectsEndedSessionTokenWithGone() throws Exception {
        TableSessionEntity session = tableSessionRepository.findBySessionToken(sessionToken).orElseThrow();
        session.end(LocalDateTime.now());
        tableSessionRepository.saveAndFlush(session);

        mockMvc.perform(get("/api/v1/menus").header("X-Session-Token", sessionToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
    }
}
