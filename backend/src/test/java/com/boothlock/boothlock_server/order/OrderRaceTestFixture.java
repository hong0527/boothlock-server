package com.boothlock.boothlock_server.order;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.dashboard.dto.ManualOrderRequest;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.dashboard.service.ManualOrderService;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.dto.OrderCreateRequest;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderCreateService;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import jakarta.persistence.EntityManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 주문 돈 흐름 안전장치 테스트 공용 데이터 — 부스 둘·메뉴·테이블·운영자 토큰을 만들고 지운다.
 * 테스트 DB를 여러 테스트 클래스가 공유하므로 FK 역순으로 전부 지운다(다른 클래스들도 deleteAll을 쓴다).
 * 세션은 테이블 파트 리포지토리의 파생 메서드 이름에 기대지 않고 JPQL·엔티티 public 메서드로만 다룬다.
 */
@Component
public class OrderRaceTestFixture {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public final BoothRepository boothRepository;
    public final StaffAccountRepository staffAccountRepository;
    public final MenuRepository menuRepository;
    public final TableRepository tableRepository;
    public final TableSessionRepository tableSessionRepository;
    public final OrderRepository orderRepository;
    public final DailyCounterRepository dailyCounterRepository;
    public final StaffCallRepository staffCallRepository;
    public final TransactionTemplate tx;
    private final EntityManager entityManager;
    private final BoothJwtProvider jwtProvider;
    private final ManualOrderService manualOrderService;
    private final OrderCreateService orderCreateService;

    public BoothEntity booth;
    public BoothEntity otherBooth;
    public TableEntity table;
    public Long kimchiId;      // 8000
    public Long colaId;        // 5000
    public String staffToken;         // loginId race-staff
    public String secondStaffToken;   // loginId race-staff-2
    public String adminToken;         // loginId race-admin
    public String otherBoothToken;
    public String superAdminToken;

    public OrderRaceTestFixture(BoothRepository boothRepository, StaffAccountRepository staffAccountRepository,
            MenuRepository menuRepository, TableRepository tableRepository,
            TableSessionRepository tableSessionRepository, OrderRepository orderRepository,
            DailyCounterRepository dailyCounterRepository, StaffCallRepository staffCallRepository,
            PlatformTransactionManager transactionManager, EntityManager entityManager,
            BoothJwtProvider jwtProvider, ManualOrderService manualOrderService,
            OrderCreateService orderCreateService) {
        this.boothRepository = boothRepository;
        this.staffAccountRepository = staffAccountRepository;
        this.menuRepository = menuRepository;
        this.tableRepository = tableRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.orderRepository = orderRepository;
        this.dailyCounterRepository = dailyCounterRepository;
        this.staffCallRepository = staffCallRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.entityManager = entityManager;
        this.jwtProvider = jwtProvider;
        this.manualOrderService = manualOrderService;
        this.orderCreateService = orderCreateService;
    }

    public void setUp() {
        cleanUp();
        booth = boothRepository.save(new BoothEntity("경합 부스", "카카오뱅크 1234", null));
        otherBooth = boothRepository.save(new BoothEntity("남의 부스", "국민은행 5678", null));
        table = tableRepository.save(new TableEntity(booth, "A-3", "race-token-a3"));
        tableRepository.save(new TableEntity(otherBooth, "A-3", "race-token-other"));
        kimchiId = menuRepository.save(new MenuEntity(booth, "김치전", 8000, null, null, true)).getId();
        colaId = menuRepository.save(new MenuEntity(booth, "제로콜라", 5000, null, null, true)).getId();
        menuRepository.save(new MenuEntity(otherBooth, "남의 메뉴", 1000, null, null, true));

        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        LocalDateTime pwdAt = LocalDateTime.of(2026, 9, 1, 12, 0);
        staffToken = issue(new StaffAccountEntity(booth, "race-staff", hash, pwdAt, StaffRole.STAFF));
        secondStaffToken = issue(new StaffAccountEntity(booth, "race-staff-2", hash, pwdAt, StaffRole.STAFF));
        adminToken = issue(new StaffAccountEntity(booth, "race-admin", hash, pwdAt, StaffRole.ADMIN));
        otherBoothToken = issue(new StaffAccountEntity(otherBooth, "race-other", hash, pwdAt, StaffRole.ADMIN));
        superAdminToken = issue(new StaffAccountEntity(null, "race-super", hash, pwdAt, StaffRole.SUPER_ADMIN));
    }

    private String issue(StaffAccountEntity staff) {
        return jwtProvider.issue(staffAccountRepository.save(staff), Instant.now());
    }

    public void cleanUp() {
        staffCallRepository.deleteAll();
        orderRepository.deleteAll();
        dailyCounterRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        menuRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public static OrderCreateRequest.OrderItemRequest item(Long menuId, int qty) {
        return new OrderCreateRequest.OrderItemRequest(menuId, qty);
    }

    /** 테이블 지정 수기 주문(O14) — 활성 세션이 없으면 만들고 붙는다 */
    public OrderCreateResponse manualOrder(Long menuId, int qty) {
        return manualOrder(List.of(item(menuId, qty)));
    }

    public OrderCreateResponse manualOrder(List<OrderCreateRequest.OrderItemRequest> items) {
        return manualOrderService.create(bearer(staffToken), null, new ManualOrderRequest(table.getId(), items)).response();
    }

    /** 손님 주문(C3) — 인증 계층을 통과했다고 보고 활성 세션 id로 바로 넣는다. 활성 세션이 없으면 새로 연다 */
    public OrderCreateResponse customerOrder(Long menuId, int qty) {
        return customerOrder(activeSessionId(), List.of(item(menuId, qty)), UUID.randomUUID().toString());
    }

    public OrderCreateResponse customerOrder(Long sessionId, List<OrderCreateRequest.OrderItemRequest> items,
                                             String idempotencyKey) {
        return orderCreateService.create(booth.getId(), sessionId, table.getLabel(), idempotencyKey,
                new OrderCreateRequest(items)).response();
    }

    /**
     * 손님 주문(C3) + 즉시 승인(O28) — 결제 확인/수정/취소 등 RECEIVED를 전제로 하는 경합만 다루는 테스트용.
     * 승인대기(PENDING_APPROVAL) 자체를 다루는 경합은 이 헬퍼를 쓰지 않는다(Figma "주문현황-승인대기" 641:1362 —
     * 승인 전 주문은 승인/거절만 가능하고 결제·수정·손님취소 대상이 아니다)
     */
    public OrderCreateResponse approvedCustomerOrder(Long menuId, int qty) {
        return approvedCustomerOrder(activeSessionId(), List.of(item(menuId, qty)), UUID.randomUUID().toString());
    }

    public OrderCreateResponse approvedCustomerOrder(Long sessionId, List<OrderCreateRequest.OrderItemRequest> items,
                                                      String idempotencyKey) {
        OrderCreateResponse response = customerOrder(sessionId, items, idempotencyKey);
        orderRepository.approve(response.orderId(), booth.getId());
        return response;
    }

    /** 테이블의 활성 세션 id — 없으면 C1이 하듯 새로 만든다 */
    public Long activeSessionId() {
        Long found = tx.execute(s -> entityManager.createQuery(
                        "select ses.id from TableSessionEntity ses where ses.table.id = :tableId and ses.endedAt is null", Long.class)
                .setParameter("tableId", table.getId())
                .getResultStream().findFirst().orElse(null));
        return found != null ? found : openSession();
    }

    /** 새 활성 세션을 연다 — 이미 열린 세션이 있으면 먼저 닫는다(uq_session_active) */
    public Long openSession() {
        endActiveSession();
        return tableSessionRepository.save(
                new TableSessionEntity(table, "race-session-" + UUID.randomUUID(), LocalDateTime.now(KST))).getId();
    }

    /**
     * 퇴실(O6)이 남기는 기록과 같은 조건부 UPDATE — ended_at·ended_at_key를 채운다. 종료된 행 수를 돌려준다.
     * 테이블 파트 서비스를 부르지 않고 흉내내는 이유: 그쪽 시그니처가 병렬 작업 중이라 바뀔 수 있다
     */
    public int endActiveSession() {
        return tx.execute(s -> endSessionIfActive(table.getId(), LocalDateTime.now(KST)));
    }

    /** 호출자의 트랜잭션 안에서 실행할 수 있는 판 — 퇴실 vs 주문 경합 테스트가 잠금 순서를 관찰할 때 쓴다 */
    public int endSessionIfActive(Long tableId, LocalDateTime endedAt) {
        return entityManager.createQuery(
                        "update TableSessionEntity ses set ses.endedAt = :endedAt, ses.endedAtKey = ses.id "
                                + "where ses.table.id = :tableId and ses.endedAt is null")
                .setParameter("endedAt", endedAt)
                .setParameter("tableId", tableId)
                .executeUpdate();
    }

    public OrderEntity reload(Long orderId) {
        return orderRepository.findByIdAndBoothId(orderId, booth.getId()).orElseThrow();
    }

    public Long itemIdOf(Long orderId, Long menuId) {
        return reload(orderId).getItems().stream().filter(i -> i.getMenuId().equals(menuId))
                .findFirst().orElseThrow().getId();
    }
}
