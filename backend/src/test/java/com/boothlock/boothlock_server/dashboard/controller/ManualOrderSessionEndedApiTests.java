package com.boothlock.boothlock_server.dashboard.controller;

import com.boothlock.boothlock_server.dashboard.PosTestFixture;
import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.order.service.OrderWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O14 — 세션을 확보한 직후, 주문 저장 전에 퇴실(O6)이 커밋되는 경합. 운영자에게 손님용 410(QR 재스캔) 대신 409를 준다.
 * 저장 경계(OrderWriter.save)를 스파이로 감싸, 주문 파트가 저장 트랜잭션 안에 넣는 세션 생존 확인(벌크 UPDATE 0건 → 410)을 흉내낸다.
 * main의 OrderWriter에는 아직 그 확인이 없어 실제 경합으로는 재현할 수 없고, 통합 뒤에도 이 매핑이 그대로 성립해야 한다.
 *
 * 세션 발급 직후·인증 직전에 퇴실이 커밋되는 틈은 여기서 재현하지 않는다 — 실측 결과 그 틈은 서비스가 감지하지 못한다(201).
 * 요청 하나가 EntityManager 하나를 끝까지 공유(OSIV)해서, 발급 때 올라온 세션 엔티티가 1차 캐시에 남고 인증 계층의
 * 재조회가 그 캐시 인스턴스(ended_at=null)를 돌려주기 때문이다. 그래서 퇴실 경합의 실제 방어선은 저장 트랜잭션 안의
 * 벌크 UPDATE(주문 파트) 하나이고, 이 클래스는 그 결과가 운영자에게 409로 나가는지만 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ManualOrderSessionEndedApiTests {

    @Autowired MockMvc mockMvc;
    @Autowired PosTestFixture fx;
    @MockitoSpyBean OrderWriter orderWriter;

    private final AtomicBoolean saveSeesEndedSession = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        fx.setUp();
        saveSeesEndedSession.set(false);

        doAnswer(invocation -> {
            OrderWriter.OrderSpec spec = invocation.getArgument(0);
            if (saveSeesEndedSession.get() && spec.sessionId() != null) {
                throw new SessionExpiredException();   // 주문 파트의 touchIfSessionActive == 0 과 같은 결과
            }
            return invocation.callRealMethod();
        }).when(orderWriter).save(any());
    }

    @AfterEach
    void tearDown() {
        fx.cleanUp();
    }

    private String body() {
        return "{\"tableId\":" + fx.table.getId() + ",\"items\":[{\"menuId\":" + fx.kimchiId + ",\"qty\":1}]}";
    }

    @Test
    void sessionEndedRightBeforeSaveIsConflictNotGone() throws Exception {
        saveSeesEndedSession.set(true);

        mockMvc.perform(post("/api/v1/admin/orders")
                        .header("Authorization", PosTestFixture.bearer(fx.staffToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("퇴실")));

        assertEquals(0, fx.orderRepository.count());
        assertEquals(0, fx.dailyCounterRepository.count());   // 저장 트랜잭션이 채번 전에 끊겨 번호도 소모되지 않는다
        // 자동으로 새 세션을 만들어 재시도하지 않는다 — 방금 퇴실한 테이블을 운영자 확인 없이 다시 점유하지 않기 위해
        assertEquals(1, fx.tableSessionRepository.count());
    }

    @Test
    void unassignedManualOrderIsNotAffectedBySessionCheck() throws Exception {
        saveSeesEndedSession.set(true);   // sessionId가 없는 M- 주문은 세션 확인 대상이 아니다

        mockMvc.perform(post("/api/v1/admin/orders")
                        .header("Authorization", PosTestFixture.bearer(fx.staffToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"menuId\":" + fx.kimchiId + ",\"qty\":1}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("M-1"));
    }

    @Test
    void withoutRaceTheSameRequestSucceeds() throws Exception {
        // 스파이가 경합을 강제하지 않을 때는 정상 201 — 위 409가 스파이 자체 때문이 아님을 고정한다
        mockMvc.perform(post("/api/v1/admin/orders")
                        .header("Authorization", PosTestFixture.bearer(fx.staffToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("A3-1"));
        assertEquals(1, fx.orderRepository.count());
    }
}
