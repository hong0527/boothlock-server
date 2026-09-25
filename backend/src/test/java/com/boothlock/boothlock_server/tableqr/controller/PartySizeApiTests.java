package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PartySizePage 제출 API(명세서 밖, 자릿세 파일럿 전용) — PATCH /api/v1/table-sessions/party-size.
 * 자릿세가 첫 주문에만 실제로 붙는지도 함께 확인한다(OrderCreateService 통합 확인, HTTP 경계에서).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PartySizeApiTests {

    private static final String SESSION_HEADER = "X-Session-Token";
    private static final String MY_TOKEN = "tok-party-size-session-00000000000000001";
    private static final LocalDateTime NOW =
            LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusMinutes(30).truncatedTo(ChronoUnit.MICROS);

    @Autowired private MockMvc mockMvc;
    @Autowired private BoothRepository boothRepository;
    @Autowired private TableRepository tableRepository;
    @Autowired private TableSessionRepository tableSessionRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private DailyCounterRepository dailyCounterRepository;

    private BoothEntity booth;
    private Long sessionId;
    private Long kimchiId;

    @BeforeEach
    void setUp() {
        booth = boothRepository.save(new BoothEntity("자릿세 부스", "은행 1234", null));
        TableEntity table = tableRepository.save(new TableEntity(booth, "A-1", "party-size-table-token"));
        sessionId = tableSessionRepository.save(new TableSessionEntity(table, MY_TOKEN, NOW)).getId();
        kimchiId = menuRepository.save(new MenuEntity(booth, "김치전", 8000, null, null, true)).getId();
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        dailyCounterRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        menuRepository.deleteAll();
        boothRepository.deleteById(booth.getId());
    }

    @Test
    void savesPartySize() throws Exception {
        mockMvc.perform(patch("/api/v1/table-sessions/party-size")
                        .header(SESSION_HEADER, MY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"partySize\":4}"))
                .andExpect(status().isOk());

        assertEquals(4, tableSessionRepository.findById(sessionId).orElseThrow().getPartySize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"partySize\":0}", "{\"partySize\":21}", "{\"partySize\":null}", "{}"})
    void rejectsOutOfRangePartySize(String body) throws Exception {
        mockMvc.perform(patch("/api/v1/table-sessions/party-size")
                        .header(SESSION_HEADER, MY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertNull(tableSessionRepository.findById(sessionId).orElseThrow().getPartySize());
    }

    @Test
    void missingSessionHeaderIsUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/v1/table-sessions/party-size")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"partySize\":2}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownTokenIsGone() throws Exception {
        mockMvc.perform(patch("/api/v1/table-sessions/party-size")
                        .header(SESSION_HEADER, "tok-does-not-exist-00000000000000000001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"partySize\":2}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
    }

    // ── 통합 확인: 자릿세가 실제로 첫 주문에만 붙는지 (HTTP 경계) ──────────────

    @Test
    void firstOrderAfterSettingPartySizeIncludesSeatFee() throws Exception {
        mockMvc.perform(patch("/api/v1/table-sessions/party-size")
                        .header(SESSION_HEADER, MY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"partySize\":2}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN)
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"menuId\":" + kimchiId + ",\"qty\":1}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(8000 + 3000 * 2))
                .andExpect(jsonPath("$.items.length()").value(2))
                // 서비스가 메뉴 항목들 뒤에 자릿세를 붙이므로 마지막 인덱스가 자릿세다
                .andExpect(jsonPath("$.items[1].itemType").value("SEAT_FEE"))
                .andExpect(jsonPath("$.items[1].menuName").value("자릿세"))
                .andExpect(jsonPath("$.items[1].menuId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[1].qty").value(2))
                .andExpect(jsonPath("$.items[0].itemType").value("MENU"));

        // 두 번째 주문엔 자릿세가 다시 붙지 않는다
        mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN)
                        .header("Idempotency-Key", "idem-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"menuId\":" + kimchiId + ",\"qty\":1}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(8000))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void orderWithoutPartySizeIsRejectedWithPartySizeRequired() throws Exception {
        // 인원 선택을 건너뛴 채(두 번째 폰·재스캔) 주문이 들어가면 그 세션 자릿세가 영구히 0원이 됐다 — 인원 선택으로 돌려보낸다
        mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN)
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"menuId\":" + kimchiId + ",\"qty\":1}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PARTY_SIZE_REQUIRED"));
    }

    @Test
    void qrRescanTellsWhetherPartySizeWasChosen() throws Exception {
        // 두 번째 폰·재스캔은 restored:true지만 아직 아무도 인원을 고르지 않았을 수 있다 — 프론트는 partySize로 인원 선택 여부를 정한다
        mockMvc.perform(post("/api/v1/table-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"party-size-table-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(true))
                .andExpect(jsonPath("$.partySize").doesNotExist());

        mockMvc.perform(patch("/api/v1/table-sessions/party-size")
                        .header(SESSION_HEADER, MY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partySize\":3}"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post("/api/v1/table-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"party-size-table-token\"}"))
                .andExpect(jsonPath("$.restored").value(true))
                .andExpect(jsonPath("$.partySize").value(3));
    }
}
