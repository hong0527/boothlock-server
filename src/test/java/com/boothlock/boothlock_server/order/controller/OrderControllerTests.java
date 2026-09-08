package com.boothlock.boothlock_server.order.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 경계 검증 — 서비스 로직은 각 서비스 테스트가 덮고, 여기서는 X-Session-Token 인증·상태코드·
 * 에러 응답 형태(§1.4)·JSON 구조가 명세 C3·C4·C5와 일치하는지 본다.
 * C3 채번이 REQUIRES_NEW로 즉시 커밋되므로 @Transactional 롤백 대신 직접 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTests {

    private static final String SESSION_HEADER = "X-Session-Token";
    private static final String MY_TOKEN = "tok-my-session-000000000000000000000001";
    private static final String OTHER_TOKEN = "tok-other-session-00000000000000000002";
    // 세션 시작 시각은 반드시 과거로 — 인증 계층이 요청마다 lastActivityAt을 "현재 시각"으로 touch하므로
    // 미래 시각을 심으면 "폴링 후 갱신됨" 단언이 성립할 수 없다
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 15, 18, 30);

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderRepository orderRepository;
    @Autowired private DailyCounterRepository dailyCounterRepository;
    @Autowired private BoothRepository boothRepository;
    @Autowired private TableRepository tableRepository;
    @Autowired private TableSessionRepository tableSessionRepository;
    @Autowired private MenuRepository menuRepository;

    private BoothEntity booth;
    private Long mySessionId;
    private Long otherSessionId;
    private Long kimchiId;
    private Long colaId;

    @BeforeEach
    void setUp() {
        booth = boothRepository.save(new BoothEntity("테스트 부스", "카카오뱅크 3333-01-1234567 (홍길동)", "18:00~02:00"));
        mySessionId = openSession("A-3", "tbl-1", MY_TOKEN);
        otherSessionId = openSession("B-7", "tbl-2", OTHER_TOKEN);
        kimchiId = menuRepository.save(new MenuEntity(booth, "김치전", 8000, null, null, true)).getId();
        colaId = menuRepository.save(new MenuEntity(booth, "제로콜라", 5000, null, null, true)).getId();
    }

    @AfterEach
    void tearDown() {
        // 롤백이 없으므로 내가 만든 것만 역순으로 지운다 — deleteAll은 다른 파트 테스트 데이터까지 지운다
        orderRepository.deleteAll();
        dailyCounterRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        menuRepository.deleteAll();
        boothRepository.deleteById(booth.getId());
    }

    private Long openSession(String label, String tableToken, String sessionToken) {
        TableEntity table = tableRepository.save(new TableEntity(booth, label, tableToken));
        return tableSessionRepository.save(new TableSessionEntity(table, sessionToken, NOW)).getId();
    }

    private OrderEntity seedOrder(Long sessionId, int orderSeq) {
        OrderEntity order = new OrderEntity(
                booth.getId(), sessionId, "A3-" + orderSeq, LocalDate.of(2026, 9, 15),
                orderSeq, "seed-" + sessionId + "-" + orderSeq, 16000, false,
                NOW.plusMinutes(orderSeq));
        order.addItem(new OrderItemEntity(kimchiId, "김치전", 8000, 2));
        return orderRepository.save(order);
    }

    private String orderBody(Long menuId, int qty) {
        return "{\"items\":[{\"menuId\":" + menuId + ",\"qty\":" + qty + "}]}";
    }

    // ── C3 주문 생성 ─────────────────────────────────────

    @Test
    void createOrderReturns201WithSpecShape() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN)
                        .header("Idempotency-Key", "idem-c3-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"menuId\":" + kimchiId + ",\"qty\":2},{\"menuId\":" + colaId + ",\"qty\":1}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("A3-1"))                      // 세션의 테이블 라벨(A-3) 정규화 + 영업일 1번
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.totalAmount").value(21000))                   // 8000×2 + 5000 — 서버 재계산
                .andExpect(jsonPath("$.items[0].menuName").value("김치전"))
                .andExpect(jsonPath("$.items[0].subtotal").value(16000))
                .andExpect(jsonPath("$.payment.method").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$.payment.bankAccount").value("카카오뱅크 3333-01-1234567 (홍길동)"))
                .andExpect(jsonPath("$.payment.depositorNameRule").value(org.hamcrest.Matchers.containsString("A3-1")));
    }

    @Test
    void sameIdempotencyKeyReturns200WithSameOrder() throws Exception {
        String first = mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN).header("Idempotency-Key", "idem-dup")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(kimchiId, 1)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN).header("Idempotency-Key", "idem-dup")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(kimchiId, 1)))
                .andExpect(status().isOk())                                          // 재요청은 201이 아니라 200
                .andReturn().getResponse().getContentAsString();

        assertEquals(first, second);                                                 // 새 주문이 아니라 같은 주문
        assertEquals(1, orderRepository.count());
    }

    @Test
    void createOrderWithoutIdempotencyKeyIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(kimchiId, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void soldOutMenuIsConflictWithDetails() throws Exception {
        MenuEntity kimchi = menuRepository.findById(kimchiId).orElseThrow();
        kimchi.updateSoldOut(true);
        menuRepository.save(kimchi);

        mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN).header("Idempotency-Key", "idem-soldout")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(kimchiId, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SOLD_OUT"))
                .andExpect(jsonPath("$.error.details[0].menuName").value("김치전"));   // 어떤 메뉴가 품절인지 details로
        assertEquals(0, orderRepository.count());                                    // 부분 주문 없음
    }

    @Test
    void closedBoothIsConflict() throws Exception {
        booth.updateOpen(false);
        boothRepository.save(booth);

        mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN).header("Idempotency-Key", "idem-closed")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(kimchiId, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_CLOSED"));
    }

    @Test
    void unknownMenuIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN).header("Idempotency-Key", "idem-unknown")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(999999L, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void ninthUnpaidOrderIsRateLimited() throws Exception {
        for (int i = 1; i <= 8; i++) {
            seedOrder(mySessionId, i);
        }
        mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN).header("Idempotency-Key", "idem-9th")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(kimchiId, 1)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("ORDER_RATE_LIMITED"));
    }

    @Test
    void replayAfterBoothClosesStillReturnsExistingOrder() throws Exception {
        // 마감 직후 네트워크 재시도 — 이미 접수된 주문의 멱등 재요청은 409로 뒤집히지 않고 200 (isOpen보다 멱등을 먼저 보는 이유)
        mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN).header("Idempotency-Key", "idem-before-close")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(kimchiId, 1)))
                .andExpect(status().isCreated());
        booth.updateOpen(false);
        boothRepository.save(booth);

        mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, MY_TOKEN).header("Idempotency-Key", "idem-before-close")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(kimchiId, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("A3-1"));
        assertEquals(1, orderRepository.count());
    }

    // ── 세션 인증 (C3·C4·C5 공통) ───────────────────────

    @Test
    void missingSessionHeaderIsUnauthorized() throws Exception {
        // 헤더 자체가 없으면 401 — 인증 필요 (§1.4)
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void unknownSessionTokenIsGone() throws Exception {
        // 헤더는 있는데 모르는 토큰 — 410, 클라이언트는 QR 재스캔 (§1.2)
        mockMvc.perform(get("/api/v1/orders").header(SESSION_HEADER, "no-such-token"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
    }

    @Test
    void endedSessionTokenIsGone() throws Exception {
        TableSessionEntity session = tableSessionRepository.findById(mySessionId).orElseThrow();
        session.end(NOW.plusHours(1));
        tableSessionRepository.save(session);

        mockMvc.perform(get("/api/v1/orders").header(SESSION_HEADER, MY_TOKEN))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
    }

    @Test
    void createAndCancelAlsoRequireValidSession() throws Exception {
        // 인증 계층은 공용이지만 엔드포인트별로 실제 연결됐는지 직접 확인한다
        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", "idem-noauth")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(kimchiId, 1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/v1/orders")
                        .header(SESSION_HEADER, "no-such-token").header("Idempotency-Key", "idem-badtoken")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(kimchiId, 1)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));

        Long orderId = seedOrder(mySessionId, 1).getId();
        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId).header(SESSION_HEADER, "no-such-token"))
                .andExpect(status().isGone());
        org.junit.jupiter.api.Assertions.assertTrue(orderRepository.findById(orderId).orElseThrow().canCancel()); // 인증 실패로 취소가 실행되지 않았다
    }

    @Test
    void bothHeadersMissingOnCreateIsUnauthorized() throws Exception {
        // 헤더 둘 다 없으면 세션 401이 먼저 — 인증 실패를 요청 형식 오류보다 먼저 알린다
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(kimchiId, 1)))
                .andExpect(status().isUnauthorized());
    }

    // ── C4 내 주문 조회 ──────────────────────────────────

    @Test
    void getOrdersReturnsOnlyMySessionOrdersNewestFirst() throws Exception {
        seedOrder(mySessionId, 1);
        seedOrder(mySessionId, 2);
        seedOrder(otherSessionId, 3);   // 남의 세션 — 섞이면 안 된다

        mockMvc.perform(get("/api/v1/orders").header(SESSION_HEADER, MY_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].orderNo").value("A3-2"))          // 최신순
                .andExpect(jsonPath("$.orders[0].canCancel").value(true))
                .andExpect(jsonPath("$.orders[0].items[0].menuName").value("김치전"))
                .andExpect(jsonPath("$.orders[0].payment.bankAccount").value("카카오뱅크 3333-01-1234567 (홍길동)"));

        // 반대 방향도 — 다른 세션은 자기 주문 1건만 본다
        mockMvc.perform(get("/api/v1/orders").header(SESSION_HEADER, OTHER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderNo").value("A3-3"));
    }

    @Test
    void pollingTouchesSessionActivity() throws Exception {
        // 명세 C4: 폴링도 세션 활동으로 인정 — 인증 계층이 last_activity_at을 갱신한다
        LocalDateTime before = tableSessionRepository.findById(mySessionId).orElseThrow().getLastActivityAt();

        mockMvc.perform(get("/api/v1/orders").header(SESSION_HEADER, MY_TOKEN)).andExpect(status().isOk());

        LocalDateTime after = tableSessionRepository.findById(mySessionId).orElseThrow().getLastActivityAt();
        org.junit.jupiter.api.Assertions.assertTrue(after.isAfter(before));
    }

    // ── C5 소비자 취소 ───────────────────────────────────

    @Test
    void cancelReturnsUpdatedOrderAsC4SingleShape() throws Exception {
        Long orderId = seedOrder(mySessionId, 1).getId();

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId).header(SESSION_HEADER, MY_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.canCancel").value(false));
    }

    @Test
    void cancelOthersOrderIsNotFound() throws Exception {
        // 존재 은닉 — 남의 주문은 403이 아니라 404 (§1.4)
        Long orderId = seedOrder(otherSessionId, 1).getId();

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId).header(SESSION_HEADER, MY_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void cancelTwiceIsConflict() throws Exception {
        Long orderId = seedOrder(mySessionId, 1).getId();
        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId).header(SESSION_HEADER, MY_TOKEN));

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId).header(SESSION_HEADER, MY_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void orderIdMustBeNumeric() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", "abc").header(SESSION_HEADER, MY_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
