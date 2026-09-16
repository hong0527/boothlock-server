package com.boothlock.boothlock_server.global.domain;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.dashboard.repository.TablePaymentOrderRepository;
import com.boothlock.boothlock_server.event.repository.BoothSeatRepository;
import com.boothlock.boothlock_server.event.repository.BoothSeatRow;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableUnpaidCountRow;
import com.boothlock.boothlock_server.tableqr.repository.TableUnpaidOrderRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 미결제 정의({@link UnpaidOrderRule})가 다섯 쿼리에서 전부 같은지 — 주문·결제 상태의 모든 조합(3×4)을 한 테이블씩 깔고
 * O3 건수·O6 경고 건수·O24 대상·유휴 예외·E1 좌석 집계가 {@link UnpaidOrderRule#matches}와 조합마다 일치해야 한다.
 * 한 곳만 옛 조건(RECEIVED만)으로 되돌리면 DONE+UNPAID 행에서 그 쿼리만 어긋나 여기서 잡힌다.
 */
@SpringBootTest
class UnpaidOrderRuleConsistencyTests {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);
    private static final LocalDateTime IDLE_SINCE = DAY.atTime(23, 0);   // 세션 활동(19:00)은 전부 이보다 앞 — 유휴, 미결제 예외로만 활성

    @Autowired BoothRepository boothRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired TableUnpaidOrderRepository tableUnpaidOrderRepository;
    @Autowired TablePaymentOrderRepository tablePaymentOrderRepository;
    @Autowired BoothSeatRepository boothSeatRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

    private BoothEntity booth;

    private record Combo(OrderStatus status, PaymentStatus paymentStatus) {
        @Override
        public String toString() {
            return status + "+" + paymentStatus;
        }
    }

    /** 조합마다 테이블 하나·열린 세션 하나·주문 하나 */
    private record Fixture(Combo combo, Long tableId, Long sessionId, Long orderId) {
    }

    @BeforeEach
    void setUp() {
        cleanUp();
        booth = boothRepository.save(new BoothEntity("정의 통일 부스", "은행 0000", null));
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        orderRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        boothRepository.deleteAll();
    }

    private List<Fixture> seedAllCombos() {
        List<Fixture> fixtures = new ArrayList<>();
        int seq = 0;
        for (OrderStatus status : OrderStatus.values()) {
            for (PaymentStatus paymentStatus : PaymentStatus.values()) {
                seq++;
                TableEntity table = new TableEntity(booth, "T-" + seq, "tok-rule-" + seq);
                table.occupy();
                table = tableRepository.save(table);
                TableSessionEntity session = tableSessionRepository.save(new TableSessionEntity(table, "sess-rule-" + seq, DAY.atTime(19, 0)));
                OrderEntity order = orderRepository.save(new OrderEntity(booth.getId(), session.getId(), "R" + seq, DAY,
                        seq, "idem-rule-" + seq, 7000, false, DAY.atTime(19, 0)));
                // 전이 규칙을 거치지 않고 조합을 직접 만든다 — 정의가 "상태 조합"만 보는지 확인하는 테스트라 경로는 무관
                jdbcTemplate.update("update orders set status = ?, payment_status = ? where id = ?",
                        status.name(), paymentStatus.name(), order.getId());
                fixtures.add(new Fixture(new Combo(status, paymentStatus), table.getId(), session.getId(), order.getId()));
            }
        }
        return fixtures;
    }

    @Test
    void ruleItselfIsReceivedOrDoneAndUnpaid() {
        assertTrue(UnpaidOrderRule.matches(OrderStatus.RECEIVED, PaymentStatus.UNPAID));
        assertTrue(UnpaidOrderRule.matches(OrderStatus.DONE, PaymentStatus.UNPAID));
        for (PaymentStatus ps : PaymentStatus.values()) {
            assertEquals(false, UnpaidOrderRule.matches(OrderStatus.CANCELED, ps), "CANCELED+" + ps);
        }
        for (OrderStatus st : OrderStatus.values()) {
            for (PaymentStatus ps : List.of(PaymentStatus.PAID, PaymentStatus.REFUND_NEEDED, PaymentStatus.REFUNDED)) {
                assertEquals(false, UnpaidOrderRule.matches(st, ps), st + "+" + ps);
            }
        }
    }

    @Test
    void fiveQueriesAgreeWithTheRuleOnEveryStatusCombination() {
        List<Fixture> fixtures = seedAllCombos();
        List<Long> tableIds = fixtures.stream().map(Fixture::tableId).toList();

        // O3 unpaidOrderCount — 테이블별 한 번에
        Map<Long, TableUnpaidCountRow> o3 = tableUnpaidOrderRepository.countUnpaidOrdersOfOpenSessions(tableIds, DAY).stream()
                .collect(Collectors.toMap(TableUnpaidCountRow::getTableId, Function.identity()));
        // E1 좌석 집계 — 부스 한 줄, emptyTables는 미결제 예외로 활성이 되지 못한 테이블 수
        BoothSeatRow e1 = boothSeatRepository.findSeatSummaries(IDLE_SINCE, DAY).stream()
                .filter(row -> row.getBoothId().equals(booth.getId())).findFirst().orElseThrow();

        Map<String, List<String>> disagreements = new LinkedHashMap<>();
        long expectedActive = 0;
        for (Fixture fx : fixtures) {
            boolean expected = UnpaidOrderRule.matches(fx.combo().status(), fx.combo().paymentStatus());
            if (expected) {
                expectedActive++;
            }
            List<String> wrong = new ArrayList<>();

            long o3Count = o3.containsKey(fx.tableId()) ? o3.get(fx.tableId()).getUnpaidOrderCount() : 0;
            if (o3Count != (expected ? 1 : 0)) {
                wrong.add("O3 unpaidOrderCount=" + o3Count);
            }
            long o6Count = transactionTemplate.execute(status ->
                    tableUnpaidOrderRepository.findUnpaidOrdersOfSessionsForUpdate(List.of(fx.sessionId()), booth.getId()).size());
            if (o6Count != (expected ? 1 : 0)) {
                wrong.add("O6 warning count=" + o6Count);
            }
            List<Long> o24 = transactionTemplate.execute(status ->
                    tablePaymentOrderRepository.findUnpaidOfActiveTableSessionsForUpdate(booth.getId(), fx.tableId())
                            .stream().map(OrderEntity::getId).toList());
            if (!o24.equals(expected ? List.of(fx.orderId()) : List.of())) {
                wrong.add("O24 targets=" + o24);
            }
            boolean idle = tableUnpaidOrderRepository.existsUnpaidOrderOn(fx.sessionId(), booth.getId(), DAY);
            if (idle != expected) {
                wrong.add("SeatIdlePolicy existsUnpaidOrderOn=" + idle);
            }
            if (!wrong.isEmpty()) {
                disagreements.put(fx.combo().toString(), wrong);
            }
        }
        assertEquals(Map.of(), disagreements, "정의와 어긋난 쿼리 (조합 → 어긋난 곳)");

        // E1은 테이블별이 아니라 부스 합계로만 나온다 — 활성(=미결제 보유) 테이블 수가 규칙의 참 조합 수와 같아야 한다
        assertEquals(fixtures.size(), e1.getTotalTables());
        assertEquals(fixtures.size() - expectedActive, e1.getEmptyTables(), "E1 emptyTables");
        assertEquals(2, expectedActive, "규칙상 미결제 조합 수 (RECEIVED+UNPAID, DONE+UNPAID)");
    }
}
