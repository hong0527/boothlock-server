package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.dashboard.service.DashboardOrderActionService;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.order.OrderRaceTestFixture;
import com.boothlock.boothlock_server.order.domain.OrderItemType;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.bearer;
import static com.boothlock.boothlock_server.order.OrderRaceTestFixture.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기타 항목(명세서 밖) — 메뉴 분류 ETC. 운영자가 설정에서 "추가 자릿세 +3000"·"쿠폰 -2000"처럼 만들어 두고
 * 결제 모달의 기타 탭에서만 넣는다. 손님 메뉴판·손님 주문에는 없고, 음수(할인)는 테이블 미결제 합계까지만 받는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EtcItemApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired OrderRaceTestFixture fx;
    @Autowired DashboardOrderActionService dashboardOrderActionService;

    private Long extraSeatFeeId;
    private Long couponId;

    @BeforeEach
    void setUp() {
        fx.setUp();
        extraSeatFeeId = etc("추가 자릿세", 3000);
        couponId = etc("쿠폰", -2000);
    }

    @AfterEach
    void tearDown() {
        fx.cleanUp();
    }

    private Long etc(String name, int price) {
        MenuEntity menu = new MenuEntity(fx.booth, name, price, null, null, true);
        menu.updateCategory("ETC");
        return fx.menuRepository.save(menu).getId();
    }

    // ── 설정(O7·O8) ─────────────────────────────

    @Test
    void etcItemMayHaveNegativePriceButRegularMenuMayNot() throws Exception {
        mockMvc.perform(post("/api/v1/admin/menus").header("Authorization", bearer(fx.staffToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"단체 할인\",\"price\":-5000,\"category\":\"ETC\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(-5000))
                .andExpect(jsonPath("$.category").value("ETC"));

        mockMvc.perform(post("/api/v1/admin/menus").header("Authorization", bearer(fx.staffToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"음수 메뉴\",\"price\":-5000,\"category\":\"MAIN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void discountItemCannotBeMovedToRegularCategory() throws Exception {
        // 음수 항목을 일반 분류로 옮기면 손님 메뉴판에 음수 가격이 뜬다
        mockMvc.perform(patch("/api/v1/admin/menus/{id}", couponId).header("Authorization", bearer(fx.staffToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"MAIN\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── 손님 쪽(C2·C3)에는 없다 ─────────────────────

    @Test
    void customerMenuBoardHidesEtcItems() throws Exception {
        fx.tableSessionRepository.save(new com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity(
                fx.table, "etc-board-session-token-0000000000000001", java.time.LocalDateTime.now(OrderRaceTestFixture.KST)));

        mockMvc.perform(get("/api/v1/menus").header("X-Session-Token", "etc-board-session-token-0000000000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus.length()").value(2))   // 김치전·제로콜라만
                .andExpect(jsonPath("$.menus[?(@.category == 'ETC')]").isEmpty());
    }

    @Test
    void customerCannotOrderEtcItemEvenWithItsId() {
        // 메뉴판에 안 보이는 쿠폰 id를 알아내 넣어도 없는 메뉴와 같은 400 — 스스로 할인받을 수 없다
        assertThrows(InvalidRequestException.class, () -> fx.customerOrder(couponId, 1));
    }

    // ── 결제 모달 기타 탭(O14) ─────────────────────

    @Test
    void etcOnlyOrderStartsDoneSoKitchenNeverSeesIt() {
        OrderCreateResponse extra = fx.manualOrder(extraSeatFeeId, 2);

        assertEquals(OrderStatus.DONE, extra.status(), "조리할 것이 없어 주방 대기열에 뜨지 않는다");
        assertEquals(6000, extra.totalAmount());
    }

    @Test
    void etcLinesOfDoneOrderCanStillBeAdjustedAndCanceled() {
        // 기타 항목만 든 주문은 완료로 시작하지만, 미결제인 동안은 결제 모달 -/취소로 고칠 수 있어야 한다
        OrderCreateResponse extra = fx.manualOrder(extraSeatFeeId, 3);
        Long itemId = fx.reload(extra.orderId()).getItems().getFirst().getId();
        assertEquals(OrderItemType.EXTRA, fx.reload(extra.orderId()).getItems().getFirst().getItemType());

        dashboardOrderActionService.updateItemQty(bearer(fx.staffToken), extra.orderId(), itemId, 1);
        assertEquals(3000, fx.reload(extra.orderId()).getTotalAmount());

        DashboardResponse.OrderSummary canceled = dashboardOrderActionService.cancelItem(
                bearer(fx.staffToken), extra.orderId(), itemId);
        assertEquals(OrderStatus.CANCELED, canceled.status(), "마지막 줄을 취소하면 주문도 취소된다");
    }

    @Test
    void paidEtcOrderCannotBeAdjusted() {
        OrderCreateResponse extra = fx.manualOrder(extraSeatFeeId, 2);
        Long itemId = fx.reload(extra.orderId()).getItems().getFirst().getId();
        dashboardOrderActionService.confirmPayment(bearer(fx.staffToken), extra.orderId(), PaymentMethod.BANK_TRANSFER);

        assertThrows(InvalidStateException.class,
                () -> dashboardOrderActionService.updateItemQty(bearer(fx.staffToken), extra.orderId(), itemId, 1));
    }

    @Test
    void mixedOrderWithMenuStaysReceived() {
        OrderCreateResponse mixed = fx.manualOrder(List.of(item(fx.kimchiId, 1), item(couponId, 1)));

        assertEquals(OrderStatus.RECEIVED, mixed.status());
        assertEquals(8000 - 2000, mixed.totalAmount());
    }

    @Test
    void discountUpToUnpaidTotalIsAccepted() {
        fx.manualOrder(fx.kimchiId, 1);   // 미결제 8000

        OrderCreateResponse coupon = fx.manualOrder(couponId, 4);   // -8000 — 딱 미결제만큼

        assertEquals(-8000, coupon.totalAmount());
        assertEquals(OrderStatus.DONE, coupon.status());
    }

    @Test
    void discountBeyondUnpaidTotalIsRejected() {
        fx.manualOrder(fx.kimchiId, 1);   // 미결제 8000

        assertThrows(InvalidStateException.class, () -> fx.manualOrder(couponId, 5));   // -10000
        assertEquals(1, fx.orderRepository.count(), "거절된 할인은 저장되지 않는다");
    }

    @Test
    void raisingCouponQtyCannotExceedUnpaidTotal() {
        // 한도는 주문을 새로 넣을 때만이 아니라 결제 모달 수량 변경에도 걸린다 — 쿠폰 줄을 +로 늘려 우회하지 못하게
        fx.manualOrder(fx.kimchiId, 1);                          // 미결제 8000
        OrderCreateResponse coupon = fx.manualOrder(couponId, 2);   // -4000 → 미결제 4000
        Long itemId = fx.reload(coupon.orderId()).getItems().getFirst().getId();

        assertThrows(InvalidStateException.class,
                () -> dashboardOrderActionService.updateItemQty(bearer(fx.staffToken), coupon.orderId(), itemId, 5));   // -10000
        assertEquals(-4000, fx.reload(coupon.orderId()).getTotalAmount(), "거절되면 수량이 그대로다");

        dashboardOrderActionService.updateItemQty(bearer(fx.staffToken), coupon.orderId(), itemId, 4);   // -8000 — 딱 한도
        assertEquals(-8000, fx.reload(coupon.orderId()).getTotalAmount());
    }

    @Test
    void cancelingLastMenuLineCannotLeaveTableNegative() {
        // 메뉴 주문의 유일한 줄을 결제 모달에서 취소하면 주문째 빠진다 — 쿠폰만 남아 미결제가 음수가 되면 409로 되돌린다
        OrderCreateResponse menu = fx.manualOrder(fx.kimchiId, 1);   // 미결제 8000
        fx.manualOrder(couponId, 2);                                // -4000
        Long kimchiLine = fx.itemIdOf(menu.orderId(), fx.kimchiId);

        assertThrows(InvalidStateException.class,
                () -> dashboardOrderActionService.cancelItem(bearer(fx.staffToken), menu.orderId(), kimchiLine));
        assertEquals(OrderStatus.RECEIVED, fx.reload(menu.orderId()).getStatus(), "거절되면 취소도 되돌려진다");
    }

    @Test
    void restoringCouponCannotLeaveTableNegative() {
        OrderCreateResponse menu = fx.manualOrder(fx.kimchiId, 1);   // 미결제 8000
        OrderCreateResponse coupon = fx.manualOrder(couponId, 2);   // -4000
        String staff = bearer(fx.staffToken);
        dashboardOrderActionService.cancelByStaff(staff, coupon.orderId(), "쿠폰 취소");
        dashboardOrderActionService.confirmPayment(staff, menu.orderId(), PaymentMethod.BANK_TRANSFER);   // 미결제 0

        assertThrows(InvalidStateException.class, () -> dashboardOrderActionService.restore(staff, coupon.orderId()));
        assertEquals(OrderStatus.CANCELED, fx.reload(coupon.orderId()).getStatus());
    }

    @Test
    void mixedOrderLeftWithOnlyCouponLeavesKitchenQueue() {
        // 메뉴+쿠폰 주문에서 메뉴만 취소하면 쿠폰만 남는다 — 조리할 게 없으니 주방 대기열(접수)에서 완료로 넘어간다
        fx.manualOrder(fx.colaId, 1);   // 다른 주문 5000 — 쿠폰 한도용
        OrderCreateResponse mixed = fx.manualOrder(List.of(item(fx.kimchiId, 1), item(couponId, 1)));

        dashboardOrderActionService.cancelItem(bearer(fx.staffToken), mixed.orderId(), fx.itemIdOf(mixed.orderId(), fx.kimchiId));

        assertEquals(OrderStatus.DONE, fx.reload(mixed.orderId()).getStatus());
        assertEquals(-2000, fx.reload(mixed.orderId()).getTotalAmount());
    }

    @Test
    void discountOnEmptyTableIsRejected() {
        assertThrows(InvalidStateException.class, () -> fx.manualOrder(couponId, 1));
    }
}
