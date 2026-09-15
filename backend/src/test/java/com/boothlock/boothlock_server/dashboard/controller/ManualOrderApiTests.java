package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableStatus;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O14 수기 주문 (명세서 O14) — C3(OrderCreateService)를 재사용하므로 여기서는 tableId 유무에 따른
 * 세션 귀속·라벨링·권한 스코프만 검증한다. 채번이 REQUIRES_NEW로 즉시 커밋되므로 롤백 대신 직접 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ManualOrderApiTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository boothRepository;
    @Autowired private StaffAccountRepository staffAccountRepository;
    @Autowired private BoothJwtProvider jwtProvider;
    @Autowired private MenuRepository menuRepository;
    @Autowired private TableRepository tableRepository;
    @Autowired private TableSessionRepository tableSessionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private DailyCounterRepository dailyCounterRepository;

    private BoothEntity booth;
    private String authorization;
    private Long menuId;

    @BeforeEach
    void setUp() {
        booth = boothRepository.save(new BoothEntity("수기주문 부스", "은행 1234", null));
        authorization = "Bearer " + issueToken(booth, "manual-staff");
        menuId = menuRepository.save(new MenuEntity(booth, "김치전", 8000, null, null, true)).getId();
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        dailyCounterRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        menuRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteById(booth.getId());
    }

    private String issueToken(BoothEntity booth, String loginId) {
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        StaffAccountEntity staff = staffAccountRepository.save(new StaffAccountEntity(
                booth, loginId, hash, LocalDateTime.of(2026, 8, 22, 12, 0), StaffRole.ADMIN));
        return jwtProvider.issue(staff, Instant.now());
    }

    private String orderBody(Long tableId, Long menuId, int qty) {
        String tablePart = tableId == null ? "" : "\"tableId\":" + tableId + ",";
        return "{" + tablePart + "\"items\":[{\"menuId\":" + menuId + ",\"qty\":" + qty + "}]}";
    }

    @Test
    void withTableIdCreatesSessionAndOccupiesTable() throws Exception {
        TableEntity table = tableRepository.save(new TableEntity(booth, "A-3", "table-token-1"));

        mockMvc.perform(post("/api/v1/admin/orders")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(table.getId(), menuId, 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("A3-1"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.totalAmount").value(16000))
                .andExpect(jsonPath("$.items[0].menuName").value("김치전"));

        TableEntity reloaded = tableRepository.findById(table.getId()).orElseThrow();
        assertEquals(TableStatus.OCCUPIED, reloaded.getStatus());
        assertEquals(1, tableSessionRepository.findByTableIdInAndEndedAtIsNull(java.util.List.of(table.getId())).size());
        assertTrue(orderRepository.findAll().stream().allMatch(o -> o.isManual()));
    }

    @Test
    void withTableIdReusesExistingActiveSession() throws Exception {
        TableEntity table = tableRepository.save(new TableEntity(booth, "B-1", "table-token-2"));
        TableSessionEntity session = tableSessionRepository.save(
                new TableSessionEntity(table, "session-token-2", LocalDateTime.of(2026, 8, 22, 17, 0)));

        mockMvc.perform(post("/api/v1/admin/orders")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(table.getId(), menuId, 1)))
                .andExpect(status().isCreated());

        assertEquals(1, tableSessionRepository.findByTableIdInAndEndedAtIsNull(java.util.List.of(table.getId())).size());
        assertEquals(session.getId(), orderRepository.findAll().get(0).getSessionId());
    }

    @Test
    void withoutTableIdUsesMPrefixAndNoSession() throws Exception {
        mockMvc.perform(post("/api/v1/admin/orders")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(null, menuId, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("M-1"));

        assertEquals(1, orderRepository.count());
        assertNotNull(orderRepository.findAll().get(0));
        assertEquals(null, orderRepository.findAll().get(0).getSessionId());
    }

    @Test
    void tableFromOtherBoothIsNotFound() throws Exception {
        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        TableEntity otherTable = tableRepository.save(new TableEntity(otherBooth, "C-1", "table-token-3"));

        mockMvc.perform(post("/api/v1/admin/orders")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(otherTable.getId(), menuId, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        tableRepository.deleteById(otherTable.getId());
        boothRepository.deleteById(otherBooth.getId());
    }

    @Test
    void soldOutMenuIsConflict() throws Exception {
        MenuEntity menu = menuRepository.findById(menuId).orElseThrow();
        menu.updateSoldOut(true);
        menuRepository.save(menu);

        mockMvc.perform(post("/api/v1/admin/orders")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(null, menuId, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SOLD_OUT"));
    }

    @Test
    void closedBoothIsConflict() throws Exception {
        booth.updateOpen(false);
        boothRepository.save(booth);

        mockMvc.perform(post("/api/v1/admin/orders")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(null, menuId, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_CLOSED"));
    }

    @Test
    void rejectsMissingAuthorizationWithUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/admin/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(null, menuId, 1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
