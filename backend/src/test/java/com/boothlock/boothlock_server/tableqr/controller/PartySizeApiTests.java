package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PartySizePage 제출 API(명세서 밖, 자릿세 파일럿 전용) — PATCH /api/v1/table-sessions/party-size.
 * 자릿세(부스 1인당 금액 × 인원수)가 첫 메뉴 주문 때 별도 자릿세 주문으로 생기는지도 함께 확인한다(OrderWriter.save, HTTP 경계에서).
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

    // ── 통합 확인: 자릿세는 첫 메뉴 주문 때 별도 주문으로 생기는지 (HTTP 경계) ──────────────

    private void choosePartySize(int partySize) throws Exception {
        mockMvc.perform(patch("/api/v1/table-sessions/party-size")
                        .header(SESSION_HEADER, MY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"partySize\":" + partySize + "}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions orderKimchi(String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/orders")
                .header(SESSION_HEADER, MY_TOKEN)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"menuId\":" + kimchiId + ",\"qty\":1}]}"));
    }

    private List<OrderEntity> sessionOrders() {
        return orderRepository.findAll().stream().filter(o -> sessionId.equals(o.getSessionId())).toList();
    }

    @Test
    void choosingPartySizeAloneChargesNothing() throws Exception {
        // 인원만 고르고 메뉴를 구경하다 간 손님에게는 청구하지 않는다
        choosePartySize(2);

        assertTrue(sessionOrders().isEmpty());
    }

    @Test
    void firstOrderCreatesSeparateSeatFeeOrder() throws Exception {
        choosePartySize(2);

        orderKimchi("idem-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(8000))   // 메뉴 주문에는 섞이지 않는다
                .andExpect(jsonPath("$.items.length()").value(1));

        // 손님 주문내역(C4)에 자릿세 주문이 따로 있다 — 조리할 게 없어 완료(DONE)·미결제, 손님은 취소 못 한다
        mockMvc.perform(get("/api/v1/orders").header(SESSION_HEADER, MY_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[?(@.items[0].itemType == 'SEAT_FEE')].status").value(Matchers.contains("DONE")))
                .andExpect(jsonPath("$.orders[?(@.items[0].itemType == 'SEAT_FEE')].paymentStatus").value(Matchers.contains("UNPAID")))
                .andExpect(jsonPath("$.orders[?(@.items[0].itemType == 'SEAT_FEE')].totalAmount").value(Matchers.contains(3000 * 2)))
                .andExpect(jsonPath("$.orders[?(@.items[0].itemType == 'SEAT_FEE')].canCancel").value(Matchers.contains(false)))
                .andExpect(jsonPath("$.orders[?(@.items[0].itemType == 'SEAT_FEE')].items[0].qty").value(Matchers.contains(2)));

        // 두 번째 주문엔 자릿세 주문이 다시 생기지 않는다
        orderKimchi("idem-2").andExpect(status().isCreated());
        assertEquals(3, sessionOrders().size());
    }

    @Test
    void seatFeeFollowsBoothSetting() throws Exception {
        booth.updateSeatFeePerPerson(5000);
        boothRepository.save(booth);
        choosePartySize(3);

        orderKimchi("idem-1").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/orders").header(SESSION_HEADER, MY_TOKEN))
                .andExpect(jsonPath("$.orders[?(@.items[0].itemType == 'SEAT_FEE')].totalAmount").value(Matchers.contains(5000 * 3)))
                .andExpect(jsonPath("$.orders[?(@.items[0].itemType == 'SEAT_FEE')].items[0].unitPrice").value(Matchers.contains(5000)));
    }

    @Test
    void zeroSeatFeeBoothChargesNothingButStillTakesPartySize() throws Exception {
        booth.updateSeatFeePerPerson(0);
        boothRepository.save(booth);
        choosePartySize(4);

        orderKimchi("idem-1").andExpect(status().isCreated());   // 인원을 골랐으니 PARTY_SIZE_REQUIRED 없이 들어간다

        assertEquals(1, sessionOrders().size(), "자릿세 주문이 없다");
        assertEquals(4, tableSessionRepository.findById(sessionId).orElseThrow().getPartySize());
    }

    @Test
    void closedBoothChargesNoSeatFee() throws Exception {
        booth.updateOpen(false);
        boothRepository.save(booth);
        choosePartySize(2);

        orderKimchi("idem-1").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORDER_CLOSED"));

        assertTrue(sessionOrders().isEmpty(), "마감된 부스 QR을 찍고 인원을 골라도 청구되지 않는다");
    }

    @Test
    void staffCanceledSeatFeeIsNotChargedAgain() throws Exception {
        choosePartySize(2);
        orderKimchi("idem-1").andExpect(status().isCreated());
        OrderEntity seatFee = sessionOrders().stream().filter(o -> o.getTotalAmount() == 3000 * 2).findFirst().orElseThrow();
        // 운영자가 자릿세 주문을 취소 = 면제 — 다음 주문에 새로 만들지 않는다
        orderRepository.cancelByStaff(seatFee.getId(), booth.getId(), "자릿세 면제", "admin", NOW.plusMinutes(1));

        orderKimchi("idem-2").andExpect(status().isCreated());

        assertEquals(3, sessionOrders().size());
        assertEquals(OrderStatus.CANCELED, orderRepository.findById(seatFee.getId()).orElseThrow().getStatus());
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
