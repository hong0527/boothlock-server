package com.boothlock.boothlock_server.order.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 경계 검증 — 서비스 로직은 각 서비스 테스트가 이미 덮고, 여기서는
 * URL·파라미터 바인딩·상태코드·에러 응답 형태(§1.4)가 명세와 일치하는지만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderControllerTests {

    private static final Long MY_SESSION = 1L;
    private static final Long OTHER_SESSION = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private BoothRepository boothRepository;

    private Long boothId;

    @BeforeEach
    void setUp() {
        boothId = boothRepository.save(
                new BoothEntity("테스트 부스", "카카오뱅크 3333-01-1234567 (홍길동)", "18:00~02:00")
        ).getId();
    }

    private OrderEntity newOrder(Long sessionId, int orderSeq) {
        OrderEntity order = new OrderEntity(
                boothId, sessionId, "A3-" + orderSeq, LocalDate.of(2026, 8, 22),
                orderSeq, "idem-" + orderSeq, 16000, sessionId == null,
                LocalDateTime.of(2026, 8, 22, 19, 0).plusMinutes(orderSeq));
        order.addItem(new OrderItemEntity(3L, "김치전", 8000, 2));
        return orderRepository.save(order);
    }

    @Test
    void getOrdersReturnsSessionOrdersAsSpecShape() throws Exception {
        newOrder(MY_SESSION, 1);
        newOrder(MY_SESSION, 2);
        newOrder(OTHER_SESSION, 3);   // 남의 주문 — 응답에 섞이면 안 된다

        mockMvc.perform(get("/api/v1/orders").param("sessionId", MY_SESSION.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].orderNo").value("A3-2"))          // 최신순
                .andExpect(jsonPath("$.orders[0].canCancel").value(true))
                .andExpect(jsonPath("$.orders[0].items[0].menuName").value("김치전"))
                .andExpect(jsonPath("$.orders[0].payment.bankAccount").value("카카오뱅크 3333-01-1234567 (홍길동)"));
    }

    @Test
    void getOrdersWithoutSessionIdIsBadRequest() throws Exception {
        // 임시 파라미터 방식이라 누락은 400 — 세션 인증 연동 후에는 401로 바뀐다 (명세서 C4)
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void cancelReturnsUpdatedOrderAsC4SingleShape() throws Exception {
        Long orderId = newOrder(MY_SESSION, 1).getId();

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                        .param("sessionId", MY_SESSION.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.canCancel").value(false));
    }

    @Test
    void cancelOthersOrderIsNotFound() throws Exception {
        // 존재 은닉 — 남의 주문은 403이 아니라 404 (명세서 §1.4)
        Long orderId = newOrder(OTHER_SESSION, 1).getId();

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                        .param("sessionId", MY_SESSION.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void cancelTwiceIsConflict() throws Exception {
        Long orderId = newOrder(MY_SESSION, 1).getId();
        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                .param("sessionId", MY_SESSION.toString()));

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                        .param("sessionId", MY_SESSION.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void orderIdMustBeNumeric() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", "abc")
                        .param("sessionId", MY_SESSION.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
