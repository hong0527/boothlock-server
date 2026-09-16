package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.dashboard.PosTestFixture;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O24 부분 실패 롤백 — 대상 주문을 잠근 뒤라 조건부 UPDATE가 자연히 실패하지는 않으므로,
 * 두 번째 주문의 UPDATE만 0건이 되게 스파이로 강제해 "앞 주문은 이미 PAID로 바뀐 상태"에서 전체가 되돌아가는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TablePaymentRollbackApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired PosTestFixture fx;
    @MockitoSpyBean OrderRepository orderRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        fx.setUp();
    }

    @AfterEach
    void tearDown() {
        fx.cleanUp();
    }

    @Test
    void failureOnSecondOrderRollsBackFirstOrder() throws Exception {
        Long first = fx.manualOrder(fx.table, fx.kimchiId, 1).orderId();
        Long second = fx.customerOrder(fx.colaId, 1).orderId();

        AtomicInteger calls = new AtomicInteger();
        // 리포지토리 스파이는 인터페이스 프록시라 실제 메서드를 부를 수 없다 — 첫 호출은 markPaid와 같은 조건부 UPDATE를
        // 같은 트랜잭션의 JDBC 연결로 실행하고(JpaTransactionManager가 연결을 공유한다), 두 번째 호출만 0건으로 만든다
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                return 0;
            }
            return jdbcTemplate.update("""
                    update orders set payment_status = 'PAID', payment_method = ?, approved_by = ?, approved_at = ?
                     where id = ? and booth_id = ? and payment_status = 'UNPAID' and status <> 'CANCELED'
                    """, invocation.getArgument(2, PaymentMethod.class).name(), invocation.getArgument(3),
                    invocation.getArgument(4), invocation.getArgument(0), invocation.getArgument(1));
        }).when(orderRepository).markPaid(any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/v1/admin/orders/table-payment")
                        .header("Authorization", PosTestFixture.bearer(fx.staffToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":" + fx.table.getId() + ",\"expectedTotal\":13000,\"method\":\"CASH\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertEquals(2, calls.get());   // 첫 주문의 UPDATE는 트랜잭션 안에서 실제로 실행됐다
        assertEquals(PaymentStatus.UNPAID, fx.orderRepository.findById(first).orElseThrow().getPaymentStatus());
        assertNull(fx.orderRepository.findById(first).orElseThrow().getApprovedBy());
        assertEquals(PaymentStatus.UNPAID, fx.orderRepository.findById(second).orElseThrow().getPaymentStatus());
    }
}
