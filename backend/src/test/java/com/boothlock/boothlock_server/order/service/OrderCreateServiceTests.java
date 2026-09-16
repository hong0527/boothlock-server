package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.OrderClosedException;
import com.boothlock.boothlock_server.global.error.OrderRateLimitedException;
import com.boothlock.boothlock_server.global.error.SoldOutException;
import com.boothlock.boothlock_server.global.error.UnauthorizedException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.dto.OrderCreateRequest;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;
import com.boothlock.boothlock_server.order.dto.OrderCreationResult;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class OrderCreateServiceTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String TABLE_LABEL = "A-3";   // 원본 표기 — orderNo는 정규화본 "A3"이어야 한다

    /** 메뉴 도메인 머지 전까지 쓰는 가짜 메뉴 창구 — 테스트가 메뉴 상태를 직접 조종한다 */
    static class FakeMenuLookup implements MenuLookup {
        private final Map<Long, MenuInfo> menus = new LinkedHashMap<>();

        void put(MenuInfo menu) {
            menus.put(menu.menuId(), menu);
        }

        void clear() {
            menus.clear();
        }

        @Override
        public List<MenuInfo> findByBoothIdAndMenuIds(Long boothId, Collection<Long> menuIds) {
            List<MenuInfo> found = new ArrayList<>();
            for (Long menuId : menuIds) {
                MenuInfo menu = menus.get(menuId);
                if (menu != null) {
                    found.add(menu);
                }
            }
            return found;
        }
    }

    @TestConfiguration
    static class FakeMenuConfig {
        @Bean
        @Primary
        FakeMenuLookup fakeMenuLookup() {
            return new FakeMenuLookup();
        }
    }

    @Autowired
    private OrderCreateService orderCreateService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private BoothRepository boothRepository;

    @Autowired
    private FakeMenuLookup menuLookup;

    @Autowired
    private DailyCounterRepository dailyCounterRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TableRepository tableRepository;

    @Autowired
    private TableSessionRepository tableSessionRepository;

    @Autowired
    private OrderNumberingService numberingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate tx;
    private Long boothId;
    private TableEntity table;
    /** 주문 저장 트랜잭션이 세션 생존을 확인하므로(OrderWriter.touchIfSessionActive) 실제 활성 세션 행이 있어야 한다 */
    private Long mySession;

    @BeforeEach
    void setUp() {
        // 채번이 진짜 커밋을 해야 하므로 @Transactional 자동 롤백을 쓰지 않고 직접 정리한다
        tx = new TransactionTemplate(transactionManager);
        orderRepository.deleteAll();
        dailyCounterRepository.deleteAll();   // 카운터가 남으면 다음 테스트의 주문번호가 밀린다
        menuLookup.clear();
        menuLookup.put(new MenuLookup.MenuInfo(3L, "김치전", 8000, false, true));
        menuLookup.put(new MenuLookup.MenuInfo(5L, "제로콜라", 5000, false, true));
        BoothEntity booth = boothRepository.save(
                new BoothEntity("테스트 부스", "카카오뱅크 3333-01-1234567 (홍길동)", "18:00~02:00"));
        boothId = booth.getId();
        table = tableRepository.save(new TableEntity(booth, TABLE_LABEL, "create-svc-table-token"));
        mySession = openSession("create-svc-session-token");
    }

    @AfterEach
    void tearDown() {
        // 롤백이 없으므로 남긴 데이터를 직접 치운다 — 안 치우면 다른 테스트 클래스의 조회 결과가 오염된다
        orderRepository.deleteAll();
        dailyCounterRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteById(table.getId());
        boothRepository.deleteById(boothId);   // 내가 만든 부스만 — deleteAll은 부스 파트 데이터까지 지운다
    }

    private Long openSession(String sessionToken) {
        return tableSessionRepository.save(new TableSessionEntity(table, sessionToken, LocalDateTime.now(KST))).getId();
    }

    /** 퇴실(O6)이 하는 일과 같은 기록 — ended_at·ended_at_key를 채운다 (테이블 파트 코드를 부르지 않고 흉내) */
    private void endSession(Long sessionId) {
        tx.executeWithoutResult(s -> {
            TableSessionEntity session = tableSessionRepository.findById(sessionId).orElseThrow();
            session.end(LocalDateTime.now(KST));
        });
    }

    private OrderCreateRequest request(Long menuId, int qty) {
        return new OrderCreateRequest(List.of(new OrderCreateRequest.OrderItemRequest(menuId, qty)));
    }

    private OrderCreationResult create(String idempotencyKey, OrderCreateRequest request) {
        // 서비스가 트랜잭션 경계를 스스로 관리한다 — 호출자가 감싸면 멱등 복구 경로가 막힌다
        return orderCreateService.create(boothId, mySession, TABLE_LABEL, idempotencyKey, request);
    }

    @Test
    void createsOrderWithServerCalculatedAmount() {
        OrderCreateRequest request = new OrderCreateRequest(List.of(
                new OrderCreateRequest.OrderItemRequest(3L, 2),
                new OrderCreateRequest.OrderItemRequest(5L, 1)));

        OrderCreationResult result = create("idem-1", request);

        assertTrue(result.created());
        OrderCreateResponse response = result.response();
        assertNotNull(response.orderId());
        assertEquals("A3-1", response.orderNo());                      // 라벨 + 영업일 통산 1번
        assertEquals(OrderStatus.RECEIVED, response.status());
        assertEquals(PaymentStatus.UNPAID, response.paymentStatus());
        assertEquals(21000, response.totalAmount());                   // 8000×2 + 5000×1 — 서버 계산
        assertEquals(2, response.items().size());
        assertEquals("김치전", response.items().get(0).menuName());     // 스냅샷
        assertEquals(16000, response.items().get(0).subtotal());        // 파생값
        assertEquals(PaymentMethod.BANK_TRANSFER, response.payment().method());
        assertEquals("카카오뱅크 3333-01-1234567 (홍길동)", response.payment().bankAccount());
        assertTrue(response.payment().depositorNameRule().contains("A3-1"));
        // 라벨 원본 스냅샷 — orderNo는 정규화본("A3")이지만 저장 라벨은 원본("A-3") (O10 표시·O19 CSV용)
        assertEquals("A-3", orderRepository.findById(response.orderId()).orElseThrow().getTableLabel());
        assertEquals(ZoneOffset.ofHours(9), response.createdAt().getOffset());
    }

    @Test
    void savesSnapshotEvenIfMenuPriceChangesLater() {
        create("idem-1", request(3L, 2));

        menuLookup.put(new MenuLookup.MenuInfo(3L, "김치전(대)", 12000, false, true));  // 메뉴 가격 인상
        create("idem-2", request(3L, 1));

        List<OrderEntity> orders = orderRepository.findBySessionIdOrderByCreatedAtDescIdDesc(mySession);
        assertEquals(2, orders.size());
        OrderEntity older = orders.get(1);
        assertEquals(8000, older.getItems().get(0).getUnitPrice());     // 과거 주문은 옛 가격 유지
        assertEquals(16000, older.getTotalAmount());
    }

    @Test
    void returnsExistingOrderForSameIdempotencyKey() {
        OrderCreationResult first = create("idem-1", request(3L, 2));
        OrderCreationResult second = create("idem-1", request(3L, 2));      // 더블탭

        assertTrue(first.created());
        assertFalse(second.created());                                  // 201이 아니라 200
        assertEquals(first.response().orderId(), second.response().orderId());
        assertEquals(1, orderRepository.count());                       // 주문은 하나만 생겼다
    }

    @Test
    void issuesSequentialOrderNumbers() {
        assertEquals("A3-1", create("idem-1", request(3L, 1)).response().orderNo());
        assertEquals("A3-2", create("idem-2", request(3L, 1)).response().orderNo());
    }

    @Test
    void rejectsWhenBoothIsClosed() {
        // BoothEntity는 부스 파트 소유라 수정하지 않는다 — 테스트는 벌크 UPDATE로 상태만 만든다
        tx.execute(status -> entityManager
                .createQuery("update BoothEntity b set b.open = false where b.id = :id")
                .setParameter("id", boothId)
                .executeUpdate());

        assertThrows(OrderClosedException.class, () -> create("idem-1", request(3L, 1)));
    }

    @Test
    void rejectsWhenUnpaidOrdersReachLimit() {
        for (int i = 1; i <= 8; i++) {
            create("idem-" + i, request(3L, 1));
        }

        // 미결제 8건까지 허용 — 9번째는 429 (명세서 C3 4단계)
        assertThrows(OrderRateLimitedException.class, () -> create("idem-9", request(3L, 1)));
    }

    @Test
    void rejectsUnknownMenuWithBadRequest() {
        assertThrows(InvalidRequestException.class, () -> create("idem-1", request(999L, 1)));
    }

    @Test
    void rejectsSoldOutMenuWithConflict() {
        menuLookup.put(new MenuLookup.MenuInfo(3L, "김치전", 8000, true, true));   // 품절

        SoldOutException e = assertThrows(SoldOutException.class, () -> create("idem-1", request(3L, 1)));
        assertEquals(1, e.getSoldOutMenus().size());
        assertEquals("김치전", e.getSoldOutMenus().get(0).menuName());   // details에 메뉴 목록
    }

    @Test
    void rejectsHiddenMenuWithConflict() {
        menuLookup.put(new MenuLookup.MenuInfo(3L, "김치전", 8000, false, false));  // 숨김

        assertThrows(SoldOutException.class, () -> create("idem-1", request(3L, 1)));
    }

    @Test
    void rejectsWholeOrderWhenOneMenuIsSoldOut() {
        menuLookup.put(new MenuLookup.MenuInfo(5L, "제로콜라", 5000, true, true));
        OrderCreateRequest request = new OrderCreateRequest(List.of(
                new OrderCreateRequest.OrderItemRequest(3L, 1),
                new OrderCreateRequest.OrderItemRequest(5L, 1)));

        assertThrows(SoldOutException.class, () -> create("idem-1", request));
        assertEquals(0, orderRepository.count());                       // 부분 주문 없음 — 전체 실패
    }

    @Test
    void rejectsInvalidQuantity() {
        assertThrows(InvalidRequestException.class, () -> create("idem-1", request(3L, 0)));
        assertThrows(InvalidRequestException.class, () -> create("idem-2", request(3L, 31)));
    }

    @Test
    void rejectsEmptyOrTooManyItems() {
        assertThrows(InvalidRequestException.class, () -> create("idem-1", new OrderCreateRequest(List.of())));

        List<OrderCreateRequest.OrderItemRequest> tooMany = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            tooMany.add(new OrderCreateRequest.OrderItemRequest((long) i, 1));
        }
        assertThrows(InvalidRequestException.class, () -> create("idem-2", new OrderCreateRequest(tooMany)));
    }

    @Test
    void rejectsMissingIdempotencyKey() {
        assertThrows(InvalidRequestException.class, () -> create(null, request(3L, 1)));
        assertThrows(InvalidRequestException.class, () -> create("  ", request(3L, 1)));
    }

    @Test
    void rejectsNullSessionId() {
        assertThrows(UnauthorizedException.class, () -> tx.execute(status ->
                orderCreateService.create(boothId, null, TABLE_LABEL, "idem-1", request(3L, 1))));
    }

    @Test
    void normalizesTableLabelForOrderNo() {
        // 라벨 원본은 "A-3"이지만 주문번호는 정규화본을 쓴다 — 하이픈·공백 제거 + 대문자 (명세서 §2)
        assertEquals("A3-1", create("idem-1", request(3L, 1)).response().orderNo());
        assertEquals("B12-2", orderCreateService.create(
                boothId, mySession, " b 12 ", "idem-2", request(3L, 1)).response().orderNo());
    }

    @Test
    void rejectsNonAlphanumericLabel() {
        // 명세 O2는 라벨을 영숫자·하이픈으로 제한한다 — 특수문자·한글이 orderNo와 입금자명 안내로 흘러가면 안 된다
        assertThrows(InvalidRequestException.class, () ->
                orderCreateService.create(boothId, mySession, "가나다", "idem-1", request(3L, 1)));
        assertThrows(InvalidRequestException.class, () ->
                orderCreateService.create(boothId, mySession, "<B>", "idem-2", request(3L, 1)));
        assertThrows(InvalidRequestException.class, () ->
                orderCreateService.create(boothId, mySession, "A#3", "idem-3", request(3L, 1)));
    }

    @Test
    void rejectsRawLabelLongerThanColumn() {
        // 정규화하면 "A3" 2자라 6자 검사는 통과하지만 원본은 table_label VARCHAR(20)을 넘는다 — 저장 전에 400으로 걸러야 500이 안 난다
        String longRaw = "A" + "-".repeat(30) + "3";
        assertThrows(InvalidRequestException.class, () ->
                orderCreateService.create(boothId, mySession, longRaw, "idem-1", request(3L, 1)));
    }

    @Test
    void stripsUnicodeSpacesFromLabel() {
        // 자바 \s는 전각 공백(U+3000)을 못 잡는다 — \p{Z}까지 지워야 orderNo에 공백이 남지 않는다
        assertEquals("A3-1", orderCreateService.create(
                boothId, mySession, "A　3", "idem-1", request(3L, 1)).response().orderNo());
        assertEquals("B7-2", orderCreateService.create(
                boothId, mySession, "B 7", "idem-2", request(3L, 1)).response().orderNo());
    }

    @Test
    void rejectsMissingOrReservedTableLabel() {
        // C3는 소비자 주문이라 테이블이 반드시 있다. 단독 M은 수기 주문(O14) 예약 라벨이라 금지 (DB스키마 §1)
        assertThrows(InvalidRequestException.class, () ->
                orderCreateService.create(boothId, mySession, null, "idem-1", request(3L, 1)));
        assertThrows(InvalidRequestException.class, () ->
                orderCreateService.create(boothId, mySession, "M", "idem-2", request(3L, 1)));
        assertThrows(InvalidRequestException.class, () ->
                orderCreateService.create(boothId, mySession, "TOOLONGLABEL", "idem-3", request(3L, 1)));
    }

    @Test
    void doesNotExposeOtherSessionOrderOnIdempotencyKeyReplay() {
        OrderCreationResult mine = create("shared-key", request(3L, 2));   // 세션 1이 만든 주문

        // 멱등키는 전역 unique라 남의 키를 보내면 남의 주문이 응답으로 새어나갈 수 있다 — 거절해야 한다
        assertThrows(InvalidRequestException.class, () -> orderCreateService.create(
                boothId, 999L, TABLE_LABEL, "shared-key", request(3L, 1)));

        assertEquals(1, orderRepository.count());
        assertEquals(mySession,
                orderRepository.findById(mine.response().orderId()).orElseThrow().getSessionId());
    }

    @Test
    void createsOnlyOneOrderWhenSameKeyArrivesConcurrently() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<OrderCreationResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();       // 같은 순간에 같은 멱등키로 돌진시킨다 (더블탭 재현)
                return orderCreateService.create(boothId, mySession, TABLE_LABEL, "race-key", request(3L, 2));
            }));
        }
        ready.await();
        start.countDown();

        int created = 0;
        Long orderId = null;
        for (Future<OrderCreationResult> future : futures) {
            OrderCreationResult result = future.get();   // 하나도 예외로 죽으면 안 된다
            if (result.created()) {
                created++;
            }
            if (orderId == null) {
                orderId = result.response().orderId();
            }
            assertEquals(orderId, result.response().orderId());   // 전부 같은 주문을 가리킨다
        }
        pool.shutdown();

        assertEquals(1, created);                                 // 새로 만든 것은 하나뿐
        assertEquals(1, orderRepository.count());
    }

    @Test
    void rejectsDuplicatedMenuInOneRequest() {
        OrderCreateRequest request = new OrderCreateRequest(List.of(
                new OrderCreateRequest.OrderItemRequest(3L, 1),
                new OrderCreateRequest.OrderItemRequest(3L, 2)));   // 같은 메뉴 두 줄

        assertThrows(InvalidRequestException.class, () -> create("idem-1", request));
    }

    @Test
    void acceptsBoundaryValues() {
        List<OrderCreateRequest.OrderItemRequest> twentyKinds = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            menuLookup.put(new MenuLookup.MenuInfo((long) i, "메뉴" + i, 1000, false, true));
            twentyKinds.add(new OrderCreateRequest.OrderItemRequest((long) i, 1));
        }

        assertEquals(20, create("idem-1", new OrderCreateRequest(twentyKinds)).response().items().size());
        assertEquals(30000, create("idem-2", request(3L, 30)).response().totalAmount());   // qty 상한 30
    }

    // ── 저장 트랜잭션 안 세션 생존 확인 (audit2 M2 / gap r3) ─────────────────

    @Test
    void rejectsOrderOnEndedSessionWithGoneAndConsumesNoNumber() {
        // 인증(컨트롤러 앞단)은 통과했지만 저장 직전에 퇴실(O6)이 커밋된 상황 — 종료된 세션에 주문이 붙으면 안 된다
        endSession(mySession);

        assertThrows(SessionExpiredException.class, () -> create("idem-1", request(3L, 1)));
        assertEquals(0, orderRepository.count());

        // 세션 확인이 채번보다 먼저라 번호는 소모되지 않는다 — 다음 손님의 첫 주문이 1번
        Long nextSession = openSession("create-svc-session-token-2");
        assertEquals("A3-1", orderCreateService.create(
                boothId, nextSession, TABLE_LABEL, "idem-2", request(3L, 1)).response().orderNo());
    }

    @Test
    void rejectsOrderOnUnknownSessionWithGone() {
        assertThrows(SessionExpiredException.class, () -> orderCreateService.create(
                boothId, 999_999L, TABLE_LABEL, "idem-1", request(3L, 1)));
        assertEquals(0, orderRepository.count());
    }

    @Test
    void touchesSessionActivityWhenOrderIsSaved() {
        LocalDateTime before = tableSessionRepository.findById(mySession).orElseThrow().getLastActivityAt();

        OrderCreateResponse response = create("idem-1", request(3L, 1)).response();

        LocalDateTime after = tableSessionRepository.findById(mySession).orElseThrow().getLastActivityAt();
        assertEquals(response.createdAt().toLocalDateTime(), after);   // 주문 시각으로 갱신
        assertFalse(after.isBefore(before));
        assertNull(tableSessionRepository.findById(mySession).orElseThrow().getEndedAt());   // 종료 표시는 건드리지 않는다
    }

    @Test
    void manualOrderOnEndedSessionIsConflictNotGone() {
        // O14는 운영자 요청이라 손님용 410이 아니라 409로 "다시 시도" 신호를 준다 (gap 2단계)
        endSession(mySession);

        assertThrows(InvalidStateException.class, () -> orderCreateService.createManual(
                boothId, mySession, "A3", TABLE_LABEL, List.of(new OrderCreateRequest.OrderItemRequest(3L, 1))));
        assertEquals(0, orderRepository.count());
    }

    @Test
    void manualOrderWithoutTableSkipsSessionCheck() {
        OrderCreateResponse response = orderCreateService.createManual(
                boothId, null, "M", null, List.of(new OrderCreateRequest.OrderItemRequest(3L, 2)));

        assertEquals("M-1", response.orderNo());
        assertEquals(16000, response.totalAmount());
    }

    // ── 수기 주문 채번 충돌 재시도 (gap "null 키 재조회") ─────────────────

    @Test
    void manualOrderNumberingConflictNeverReturnsAnotherManualOrder() {
        // 카운터가 다음에 뽑을 번호(1)를 미리 점유한 수기 주문 행(멱등키 null, 같은 세션)을 심어 uq_orders_seq 충돌을 강제한다.
        // 수정 전에는 충돌 뒤 findByIdempotencyKey(null)이 IS NULL 조회로 바뀌어 이 행이 "같은 키의 기존 주문"으로 잡혔고,
        // 세션·부스가 같으니 남의 옛 주문을 방금 만든 주문인 것처럼 돌려줬다(테이블 미지정이면 sessionId null 비교 NPE).
        LocalDate businessDate = numberingService.businessDateOf(LocalDateTime.now(KST));
        OrderEntity squatter = new OrderEntity(boothId, mySession, "A3-1", businessDate, 1, null, 5000, true,
                TABLE_LABEL, LocalDateTime.now(KST).minusMinutes(1));
        squatter.addItem(new OrderItemEntity(5L, "제로콜라", 5000, 1));
        orderRepository.save(squatter);

        // 채번 증가는 실패한 저장과 같은 트랜잭션에서 롤백되므로 재시도마다 다시 1번이 나와 3회 모두 충돌한다 —
        // 카운터와 orders가 어긋난 비정상 상태라 조용히 다른 주문을 돌려주는 대신 제약 위반으로 실패해야 한다
        assertThrows(DataIntegrityViolationException.class, () -> orderCreateService.createManual(
                boothId, mySession, "A3", TABLE_LABEL, List.of(new OrderCreateRequest.OrderItemRequest(3L, 1))));
        assertEquals(1, orderRepository.count());   // 새 주문도, 잘못 돌려준 주문도 없다

        // 테이블 미지정(세션 없음) 수기 주문도 같은 충돌에서 NPE 대신 제약 위반으로 끝난다
        OrderEntity manualSquatter = new OrderEntity(boothId, null, "M-1", businessDate, 1, null, 5000, true,
                LocalDateTime.now(KST).minusMinutes(1));
        orderRepository.deleteAll();
        orderRepository.save(manualSquatter);
        assertThrows(DataIntegrityViolationException.class, () -> orderCreateService.createManual(
                boothId, null, "M", null, List.of(new OrderCreateRequest.OrderItemRequest(3L, 1))));
    }

    // ── 멱등 재응답의 취소 항목 제외 (audit2 M3) ─────────────────

    @Test
    void replayResponseExcludesItemsCanceledInPaymentModal() {
        OrderCreateRequest request = new OrderCreateRequest(List.of(
                new OrderCreateRequest.OrderItemRequest(3L, 2),
                new OrderCreateRequest.OrderItemRequest(5L, 1)));
        OrderCreateResponse first = create("idem-1", request).response();
        assertEquals(2, first.items().size());

        // 결제 모달에서 콜라 항목을 개별 취소한 상태를 흉내낸다 — 항목은 숨김, 합계는 재계산
        Long colaItemId = orderRepository.findByIdAndBoothId(first.orderId(), boothId).orElseThrow().getItems().stream()
                .filter(item -> item.getMenuId().equals(5L)).findFirst().orElseThrow().getId();
        jdbcTemplate.update("update order_item set canceled = true where id = ?", colaItemId);
        jdbcTemplate.update("update orders set total_amount = 16000 where id = ?", first.orderId());

        OrderCreationResult replayed = create("idem-1", request);   // 손님 더블탭 재요청

        assertFalse(replayed.created());
        assertEquals(1, replayed.response().items().size());
        assertEquals(3L, replayed.response().items().get(0).menuId());
        assertEquals(16000, replayed.response().totalAmount());
        assertEquals(replayed.response().items().stream().mapToInt(OrderCreateResponse.OrderItemResponse::subtotal).sum(),
                replayed.response().totalAmount());   // 항목 합 == 합계
    }
}
