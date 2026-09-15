package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.dashboard.domain.CallReason;
import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class DashboardQueryServiceTests {

    @Autowired
    private DashboardQueryService dashboardQueryService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private BoothRepository boothRepository;

    @Autowired
    private StaffAccountRepository staffAccountRepository;

    @Autowired
    private BoothJwtProvider jwtProvider;

    @Autowired
    private TableRepository tableRepository;

    @Autowired
    private TableSessionRepository tableSessionRepository;

    @Autowired
    private StaffCallRepository staffCallRepository;

    @Autowired
    private EntityManager entityManager;

    private Long boothId;
    private Long sessionId;
    private String authorization;

    @BeforeEach
    void setUp() {
        BoothEntity booth = boothRepository.save(
                new BoothEntity("테스트 부스", "카카오뱅크 3333-01-1234567 (홍길동)", "18:00~02:00"));
        boothId = booth.getId();
        authorization = "Bearer " + issueToken(booth, "dashboard-staff");

        TableEntity table = tableRepository.save(
                new TableEntity(entityManager.getReference(BoothEntity.class, boothId), "A3", "table-token-1"));
        sessionId = tableSessionRepository.save(
                new TableSessionEntity(table, "session-token-1", LocalDateTime.of(2026, 8, 22, 17, 0))
        ).getId();
    }

    private String issueToken(BoothEntity booth, String loginId) {
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        StaffAccountEntity staff = staffAccountRepository.save(new StaffAccountEntity(
                booth, loginId, hash, LocalDateTime.of(2026, 8, 22, 12, 0), StaffRole.ADMIN));
        return jwtProvider.issue(staff, Instant.now());
    }

    private OrderEntity newOrder(Long boothId, int orderSeq, LocalDateTime createdAt) {
        OrderEntity order = new OrderEntity(
                boothId, sessionId, "A3-" + orderSeq, LocalDate.of(2026, 8, 22),
                orderSeq, "idem-" + boothId + "-" + orderSeq, 16000, false, createdAt);
        order.addItem(new OrderItemEntity(3L, "김치전", 8000, 2));
        return orderRepository.save(order);
    }

    private void newCall(CallReason reason, boolean acked) {
        TableSessionEntity session = entityManager.getReference(TableSessionEntity.class, sessionId);
        StaffCallEntity call = new StaffCallEntity(session, reason, LocalDateTime.of(2026, 8, 22, 18, 0));
        if (acked) {
            entityManager.persist(call);
            entityManager.createQuery("update StaffCallEntity c set c.acked = true where c = :c")
                    .setParameter("c", call).executeUpdate();
        } else {
            staffCallRepository.save(call);
        }
    }

    @Test
    void returnsOrdersAndUnackedCallsForBooth() {
        newOrder(boothId, 1, LocalDateTime.of(2026, 8, 22, 18, 0));
        newCall(CallReason.HELP, false);
        newCall(CallReason.WATER, true);   // 확인 처리된 호출은 제외

        entityManager.flush();
        entityManager.clear();

        DashboardResponse response = dashboardQueryService.getDashboard(authorization, null, null, null, null);

        assertEquals(1, response.orders().size());
        assertEquals("A3-1", response.orders().get(0).orderNo());
        assertEquals(1, response.calls().size());
        assertEquals("HELP", response.calls().get(0).reason());
        assertEquals("A3", response.calls().get(0).tableLabel());
    }

    @Test
    void excludesOtherBoothOrdersAndCalls() {
        BoothEntity otherBooth = boothRepository.save(
                new BoothEntity("다른 부스", "국민은행 123-456 (김철수)", "17:00~01:00"));
        String otherAuthorization = "Bearer " + issueToken(otherBooth, "other-staff");

        newOrder(boothId, 1, LocalDateTime.of(2026, 8, 22, 18, 0));
        newOrder(otherBooth.getId(), 2, LocalDateTime.of(2026, 8, 22, 18, 5));
        newCall(CallReason.HELP, false);

        entityManager.flush();
        entityManager.clear();

        DashboardResponse response = dashboardQueryService.getDashboard(otherAuthorization, null, null, null, null);

        assertEquals(1, response.orders().size());
        assertEquals("A3-2", response.orders().get(0).orderNo());
        assertEquals(0, response.calls().size());   // 호출은 otherBooth 테이블에 없으므로 0건
    }

    @Test
    void filtersByStatusPaymentStatusAndBusinessDate() {
        Long paid = newOrder(boothId, 1, LocalDateTime.of(2026, 8, 22, 18, 0)).getId();
        newOrder(boothId, 2, LocalDateTime.of(2026, 8, 22, 18, 10));

        entityManager.flush();
        entityManager.createQuery("update OrderEntity o set o.paymentStatus = :ps where o.id = :id")
                .setParameter("ps", PaymentStatus.PAID).setParameter("id", paid).executeUpdate();
        entityManager.clear();

        List<DashboardResponse.OrderSummary> orders =
                dashboardQueryService.getDashboard(authorization, null, PaymentStatus.PAID, null, null).orders();

        assertEquals(1, orders.size());
        assertEquals("A3-1", orders.get(0).orderNo());
    }

    @Test
    void filtersByOrderNoSearch() {
        newOrder(boothId, 7, LocalDateTime.of(2026, 8, 22, 18, 0));
        newOrder(boothId, 8, LocalDateTime.of(2026, 8, 22, 18, 5));

        entityManager.flush();
        entityManager.clear();

        List<DashboardResponse.OrderSummary> orders =
                dashboardQueryService.getDashboard(authorization, null, null, null, "A3-7").orders();

        assertEquals(1, orders.size());
        assertEquals("A3-7", orders.get(0).orderNo());
    }

    @Test
    void returnsEmptyResultForBoothWithoutData() {
        BoothEntity emptyBooth = boothRepository.save(
                new BoothEntity("빈 부스", "은행 0000-00", null));
        String emptyAuthorization = "Bearer " + issueToken(emptyBooth, "empty-staff");

        DashboardResponse response = dashboardQueryService.getDashboard(emptyAuthorization, null, null, null, null);

        assertTrue(response.orders().isEmpty());
        assertTrue(response.calls().isEmpty());
    }
}
