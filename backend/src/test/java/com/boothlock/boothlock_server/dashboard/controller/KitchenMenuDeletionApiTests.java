package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.dashboard.PosTestFixture;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KitchenMenuDeletionApiTests {
    @Autowired MockMvc mockMvc;
    @Autowired PosTestFixture fx;

    @BeforeEach void setUp() { fx.setUp(); }
    @AfterEach void cleanUp() { fx.cleanUp(); }

    @Test
    void deletingMenuPreservesReceivedOrderNameAndQuantity() throws Exception {
        Long menuId = fx.menuRepository.save(new MenuEntity(fx.booth, "메뉴 A", 1000, null, null, true)).getId();
        Long orderId = fx.manualOrder(fx.table, menuId, 2).orderId();
        assertReceivedSnapshot(orderId, menuId);

        // 설정 화면에서 사용하는 실제 메뉴 삭제 API를 실행한다.
        mockMvc.perform(delete("/api/v1/admin/menus/{menuId}", menuId)
                        .header("Authorization", PosTestFixture.bearer(fx.adminToken)))
                .andExpect(status().isNoContent());
        assertFalse(fx.menuRepository.existsById(menuId));
        mockMvc.perform(get("/api/v1/admin/menus")
                        .header("Authorization", PosTestFixture.bearer(fx.adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[?(@.name == '메뉴 A')]").isEmpty());

        // 삭제 이후 새 조회에서도 주문 스냅샷이 남아 주방 집계 입력이 사라지지 않는다.
        assertReceivedSnapshot(orderId, menuId);
    }

    private void assertReceivedSnapshot(Long orderId, Long menuId) throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders").param("status", "RECEIVED")
                        .header("Authorization", PosTestFixture.bearer(fx.adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderId").value(orderId))
                .andExpect(jsonPath("$.orders[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.orders[0].items[0].menuId").value(menuId))
                .andExpect(jsonPath("$.orders[0].items[0].menuName").value("메뉴 A"))
                .andExpect(jsonPath("$.orders[0].items[0].qty").value(2))
                .andExpect(jsonPath("$.orders[0].items[0].itemType").value("MENU"));
    }
}
