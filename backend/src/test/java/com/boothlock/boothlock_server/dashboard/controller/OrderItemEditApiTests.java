package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.order.OrderRaceTestFixture;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.bearer;
import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결제 모달 항목 수량 변경(PATCH …/items/{itemId})·개별 취소(POST …/items/{itemId}/cancel) API —
 * 프론트(PaymentModal.tsx·orderActions.ts)가 쓰는 경로·본문·응답 필드는 main 계약 그대로 유지한 채,
 * 접수+미결제만·증가 시 품절/마감 검사·서버 재계산·마지막 항목은 O13 경로로 전체 취소·취소 항목 숨김을 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderItemEditApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired OrderRaceTestFixture fx;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OrderNumberingService numberingService;

    @BeforeEach
    void setUp() {
        fx.setUp();
    }

    @AfterEach
    void tearDown() {
        fx.cleanUp();
    }

    private MockHttpServletRequestBuilder qty(String token, Long orderId, Long itemId, String body) {
        return patch("/api/v1/admin/orders/{orderId}/items/{itemId}", orderId, itemId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder cancel(String token, Long orderId, Long itemId) {
        return post("/api/v1/admin/orders/{orderId}/items/{itemId}/cancel", orderId, itemId)
                .header("Authorization", bearer(token));
    }

    private MockHttpServletRequestBuilder pay(String token, Long orderId) {
        return patch("/api/v1/admin/orders/{orderId}/payment", orderId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"CASH\"}");
    }

    /** 김치전 3 + 콜라 2 = 34000 */
    private Long twoItemOrder() {
        return fx.manualOrder(List.of(item(fx.kimchiId, 3), item(fx.colaId, 2))).orderId();
    }

    private int liveItemSum(Long orderId) {
        return fx.reload(orderId).getItems().stream().filter(i -> !i.isCanceled()).mapToInt(OrderItemEntity::subtotal).sum();
    }

    private String sessionToken() {
        return fx.tableSessionRepository.findById(fx.activeSessionId()).map(TableSessionEntity::getSessionToken).orElseThrow();
    }

    // ── 수량 변경 ────────────────────────────────────────

    @Test
    void decreasesQtyAndRecalculatesTotalFromSnapshotPrice() throws Exception {
        Long orderId = twoItemOrder();
        Long kimchiItem = fx.itemIdOf(orderId, fx.kimchiId);

        // 메뉴 가격이 바뀌어도 이미 들어간 주문의 단가 스냅샷으로 계산한다
        MenuEntity kimchi = fx.menuRepository.findById(fx.kimchiId).orElseThrow();
        kimchi.updatePrice(99000);
        fx.menuRepository.save(kimchi);

        mockMvc.perform(qty(fx.staffToken, orderId, kimchiItem, "{\"qty\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.totalAmount").value(18000))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.itemId == " + kimchiItem + ")].qty").value(Matchers.contains(1)))
                .andExpect(jsonPath("$.items[?(@.itemId == " + kimchiItem + ")].unitPrice").value(Matchers.contains(8000)))
                .andExpect(jsonPath("$.items[0].itemId").exists())
                .andExpect(jsonPath("$.items[0].menuName").exists());

        assertEquals(18000, fx.reload(orderId).getTotalAmount());
        assertEquals(liveItemSum(orderId), fx.reload(orderId).getTotalAmount());
    }

    @Test
    void increaseIsAllowedForOrderableMenu() throws Exception {
        // 프론트 "+" 버튼 계약 유지 — 판매 중인 메뉴는 늘릴 수 있다 (팀장 결정: 증가 허용, 단 검사)
        Long orderId = twoItemOrder();
        Long colaItem = fx.itemIdOf(orderId, fx.colaId);

        mockMvc.perform(qty(fx.staffToken, orderId, colaItem, "{\"qty\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(39000));
        assertEquals(39000, fx.reload(orderId).getTotalAmount());
    }

    @Test
    void increaseIsRejectedForSoldOutOrHiddenMenuButDecreaseStillWorks() throws Exception {
        Long orderId = twoItemOrder();
        Long kimchiItem = fx.itemIdOf(orderId, fx.kimchiId);

        MenuEntity kimchi = fx.menuRepository.findById(fx.kimchiId).orElseThrow();
        kimchi.updateSoldOut(true);
        fx.menuRepository.save(kimchi);

        mockMvc.perform(qty(fx.staffToken, orderId, kimchiItem, "{\"qty\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SOLD_OUT"))
                .andExpect(jsonPath("$.error.details[0].menuId").value(fx.kimchiId));
        assertEquals(34000, fx.reload(orderId).getTotalAmount());

        // 품절이어도 줄이는 건 된다 — 손님 요청 반영
        mockMvc.perform(qty(fx.staffToken, orderId, kimchiItem, "{\"qty\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(26000));

        kimchi.updateSoldOut(false);
        kimchi.updateVisible(false);
        fx.menuRepository.save(kimchi);
        mockMvc.perform(qty(fx.staffToken, orderId, kimchiItem, "{\"qty\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SOLD_OUT"));
        assertEquals(26000, fx.reload(orderId).getTotalAmount());
    }

    @Test
    void increaseIsRejectedWhenBoothIsClosed() throws Exception {
        Long orderId = twoItemOrder();
        Long colaItem = fx.itemIdOf(orderId, fx.colaId);

        BoothEntity booth = fx.boothRepository.findById(fx.booth.getId()).orElseThrow();
        booth.updateOpen(false);
        fx.boothRepository.save(booth);

        mockMvc.perform(qty(fx.staffToken, orderId, colaItem, "{\"qty\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_CLOSED"));
        // 마감 중에도 줄이기·같은 수량은 된다
        mockMvc.perform(qty(fx.staffToken, orderId, colaItem, "{\"qty\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(29000));
    }

    @Test
    void rejectsQtyOutOfRangeAndMalformedBody() throws Exception {
        Long orderId = twoItemOrder();
        Long kimchiItem = fx.itemIdOf(orderId, fx.kimchiId);

        for (String body : List.of("{\"qty\":0}", "{\"qty\":31}", "{\"qty\":-1}", "{}", "{\"qty\":\"x\"}")) {
            mockMvc.perform(qty(fx.staffToken, orderId, kimchiItem, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertEquals(34000, fx.reload(orderId).getTotalAmount());
    }

    @Test
    void rejectsEditOnPaidDoneOrCanceledOrders() throws Exception {
        Long paid = twoItemOrder();
        mockMvc.perform(pay(fx.staffToken, paid)).andExpect(status().isOk());
        mockMvc.perform(qty(fx.staffToken, paid, fx.itemIdOf(paid, fx.kimchiId), "{\"qty\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        mockMvc.perform(cancel(fx.staffToken, paid, fx.itemIdOf(paid, fx.kimchiId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        // 입금 후에는 상태 충돌이 다른 검사보다 먼저다 — 품절 메뉴 증가 요청도 SOLD_OUT이 아니라 INVALID_STATE, 남의 항목도 404가 아니라 409
        MenuEntity kimchi = fx.menuRepository.findById(fx.kimchiId).orElseThrow();
        kimchi.updateSoldOut(true);
        fx.menuRepository.save(kimchi);
        mockMvc.perform(qty(fx.staffToken, paid, fx.itemIdOf(paid, fx.kimchiId), "{\"qty\":9}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        mockMvc.perform(qty(fx.staffToken, paid, 999999L, "{\"qty\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        kimchi.updateSoldOut(false);
        fx.menuRepository.save(kimchi);
        assertEquals(34000, fx.reload(paid).getTotalAmount());
        assertEquals(PaymentStatus.PAID, fx.reload(paid).getPaymentStatus());

        Long done = twoItemOrder();
        jdbcTemplate.update("update orders set status = 'DONE' where id = ?", done);
        mockMvc.perform(qty(fx.staffToken, done, fx.itemIdOf(done, fx.kimchiId), "{\"qty\":1}"))
                .andExpect(status().isConflict());
        mockMvc.perform(cancel(fx.staffToken, done, fx.itemIdOf(done, fx.kimchiId)))
                .andExpect(status().isConflict());

        Long canceled = twoItemOrder();
        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", canceled)
                        .header("Authorization", bearer(fx.staffToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"손님 요청\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(qty(fx.staffToken, canceled, fx.itemIdOf(canceled, fx.kimchiId), "{\"qty\":1}"))
                .andExpect(status().isConflict());
        mockMvc.perform(cancel(fx.staffToken, canceled, fx.itemIdOf(canceled, fx.kimchiId)))
                .andExpect(status().isConflict());
        assertEquals("손님 요청", fx.reload(canceled).getCancelReason());   // O13 기록은 그대로
    }

    // ── 개별 취소 ────────────────────────────────────────

    @Test
    void cancelingOneItemHidesItEverywhereAndKeepsTotalsConsistent() throws Exception {
        String idemKey = UUID.randomUUID().toString();
        Long sessionId = fx.activeSessionId();
        OrderCreateResponse created = fx.customerOrder(sessionId, List.of(item(fx.kimchiId, 2), item(fx.colaId, 1)), idemKey);
        Long orderId = created.orderId();
        Long kimchiItem = fx.itemIdOf(orderId, fx.kimchiId);

        mockMvc.perform(cancel(fx.staffToken, orderId, kimchiItem))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.totalAmount").value(5000))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].menuName").value("제로콜라"))
                .andExpect(jsonPath("$.canceledBy").doesNotExist());

        // DB: 행은 남고 숨김 표시만
        OrderEntity saved = fx.reload(orderId);
        assertEquals(2, saved.getItems().size());
        assertTrue(saved.getItems().stream().filter(i -> i.getId().equals(kimchiItem)).findFirst().orElseThrow().isCanceled());
        assertEquals(5000, saved.getTotalAmount());
        assertEquals(liveItemSum(orderId), saved.getTotalAmount());

        // O10 대시보드·C4 손님 화면·C3 멱등 재응답 모두 같은 항목 목록과 합계
        mockMvc.perform(get("/api/v1/admin/orders").header("Authorization", bearer(fx.staffToken)))
                .andExpect(jsonPath("$.orders[0].totalAmount").value(5000))
                .andExpect(jsonPath("$.orders[0].items.length()").value(1));
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", sessionToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].totalAmount").value(5000))
                .andExpect(jsonPath("$.orders[0].items.length()").value(1))
                .andExpect(jsonPath("$.orders[0].items[0].menuName").value("제로콜라"));
        mockMvc.perform(post("/api/v1/orders")
                        .header("X-Session-Token", sessionToken())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"menuId\":" + fx.kimchiId + ",\"qty\":2},{\"menuId\":" + fx.colaId + ",\"qty\":1}]}"))
                .andExpect(status().isOk())   // 재요청은 200
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.totalAmount").value(5000))
                .andExpect(jsonPath("$.items.length()").value(1));

        // 같은 항목 재취소는 404 — 이미 숨긴 항목은 없는 것과 같다
        mockMvc.perform(cancel(fx.staffToken, orderId, kimchiItem))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelingLastItemCancelsWholeOrderThroughStaffCancelPathAndKeepsRow() throws Exception {
        Long orderId = fx.manualOrder(fx.kimchiId, 2).orderId();
        Long itemId = fx.itemIdOf(orderId, fx.kimchiId);

        mockMvc.perform(cancel(fx.staffToken, orderId, itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))   // 입금 전이라 환불 대상이 아님
                .andExpect(jsonPath("$.canceledBy").value("race-staff"))
                .andExpect(jsonPath("$.canceledAt").exists())
                .andExpect(jsonPath("$.cancelReason").value("전체 항목 취소"))
                .andExpect(jsonPath("$.totalAmount").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));

        OrderEntity saved = fx.reload(orderId);
        assertEquals(OrderStatus.CANCELED, saved.getStatus());
        assertEquals(PaymentStatus.UNPAID, saved.getPaymentStatus());
        assertEquals(0, saved.getTotalAmount());
        assertEquals(1, saved.getItems().size());                 // 행은 지우지 않는다 (감사·정산 기록)
        assertTrue(saved.getItems().get(0).isCanceled());
        assertEquals(1, jdbcTemplate.queryForObject("select count(*) from order_item where order_id = ?", Integer.class, orderId));

        // 이후 입금 확인은 409, O13 재취소도 409 — 취소 기록을 덮어쓰지 않는다
        mockMvc.perform(pay(fx.staffToken, orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/cancel", orderId)
                        .header("Authorization", bearer(fx.secondStaffToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"덮어쓰기 시도\"}"))
                .andExpect(status().isConflict());
        OrderEntity after = fx.reload(orderId);
        assertEquals("race-staff", after.getCanceledBy());
        assertEquals("전체 항목 취소", after.getCancelReason());
        assertEquals(PaymentStatus.UNPAID, after.getPaymentStatus());
        assertNull(after.getApprovedBy());
    }

    @Test
    void cancelingBothItemsSequentiallyEndsCanceledWithZeroTotal() throws Exception {
        Long orderId = twoItemOrder();

        mockMvc.perform(cancel(fx.staffToken, orderId, fx.itemIdOf(orderId, fx.kimchiId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.totalAmount").value(10000));
        mockMvc.perform(cancel(fx.staffToken, orderId, fx.itemIdOf(orderId, fx.colaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.totalAmount").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
        assertEquals(2, fx.reload(orderId).getItems().size());
    }

    // ── 스코프·인증 ────────────────────────────────────────

    @Test
    void hidesOtherBoothOrderAndForeignItemAsNotFound() throws Exception {
        Long mine = twoItemOrder();
        Long another = fx.manualOrder(fx.colaId, 2).orderId();

        mockMvc.perform(qty(fx.otherBoothToken, mine, fx.itemIdOf(mine, fx.kimchiId), "{\"qty\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(cancel(fx.otherBoothToken, mine, fx.itemIdOf(mine, fx.kimchiId)))
                .andExpect(status().isNotFound());
        // 다른 주문의 항목 id를 끼워 넣어도 404 — 남의 주문 금액을 바꾸지 못한다
        mockMvc.perform(qty(fx.staffToken, mine, fx.itemIdOf(another, fx.colaId), "{\"qty\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(cancel(fx.staffToken, mine, fx.itemIdOf(another, fx.colaId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(qty(fx.staffToken, 999999L, 1L, "{\"qty\":1}"))
                .andExpect(status().isNotFound());

        assertEquals(34000, fx.reload(mine).getTotalAmount());
        assertEquals(10000, fx.reload(another).getTotalAmount());
        assertTrue(fx.reload(another).getItems().stream().noneMatch(OrderItemEntity::isCanceled));
    }

    @Test
    void requiresBoothStaffToken() throws Exception {
        Long orderId = twoItemOrder();
        Long itemId = fx.itemIdOf(orderId, fx.kimchiId);

        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/items/{itemId}", orderId, itemId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/orders/{orderId}/items/{itemId}/cancel", orderId, itemId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(qty(fx.superAdminToken, orderId, itemId, "{\"qty\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(cancel(fx.superAdminToken, orderId, itemId))
                .andExpect(status().isForbidden());
        mockMvc.perform(qty(fx.adminToken, orderId, itemId, "{\"qty\":2}"))
                .andExpect(status().isOk());
        assertEquals(26000, fx.reload(orderId).getTotalAmount());
    }

    // ── 매출 집계 ────────────────────────────────────────

    @Test
    void salesStatsReflectReducedAmountAfterPayment() throws Exception {
        Long orderId = twoItemOrder();
        mockMvc.perform(qty(fx.staffToken, orderId, fx.itemIdOf(orderId, fx.kimchiId), "{\"qty\":1}"))
                .andExpect(jsonPath("$.totalAmount").value(18000));
        mockMvc.perform(cancel(fx.staffToken, orderId, fx.itemIdOf(orderId, fx.colaId)))
                .andExpect(jsonPath("$.totalAmount").value(8000));
        mockMvc.perform(patch("/api/v1/admin/orders/{orderId}/payment", orderId)
                        .header("Authorization", bearer(fx.staffToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(8000));

        // 마지막 항목까지 취소한 주문(CANCELED·0원)은 매출에 잡히지 않는다
        Long canceledWhole = fx.manualOrder(fx.colaId, 1).orderId();
        mockMvc.perform(cancel(fx.staffToken, canceledWhole, fx.itemIdOf(canceledWhole, fx.colaId)))
                .andExpect(jsonPath("$.status").value("CANCELED"));

        String businessDate = numberingService.businessDateOf(fx.reload(orderId).getCreatedAt()).toString();
        mockMvc.perform(get("/api/v1/admin/stats/sales").param("date", businessDate)
                        .header("Authorization", bearer(fx.adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSales").value(8000))
                .andExpect(jsonPath("$.byMethod.BANK_TRANSFER").value(8000))
                .andExpect(jsonPath("$.paidOrderCount").value(1));
        assertNotNull(fx.reload(orderId).getApprovedBy());
    }
}
