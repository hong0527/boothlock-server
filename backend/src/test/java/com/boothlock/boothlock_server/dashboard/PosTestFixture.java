package com.boothlock.boothlock_server.dashboard;

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
import com.boothlock.boothlock_server.order.dto.OrderCreateRequest;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderCreateService;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 대시보드 O24·보안 테스트 공용 데이터 — 부스 둘·메뉴·테이블·운영자 토큰을 만들고 지운다.
 * 테스트 DB를 여러 테스트 클래스가 공유하므로 FK 역순으로 전부 지운다(다른 클래스들도 deleteAll을 쓴다).
 */
@Component
public class PosTestFixture {

    public final BoothRepository boothRepository;
    public final StaffAccountRepository staffAccountRepository;
    public final MenuRepository menuRepository;
    public final TableRepository tableRepository;
    public final TableSessionRepository tableSessionRepository;
    public final OrderRepository orderRepository;
    public final DailyCounterRepository dailyCounterRepository;
    public final StaffCallRepository staffCallRepository;
    private final BoothJwtProvider jwtProvider;
    private final ManualOrderService manualOrderService;
    private final OrderCreateService orderCreateService;

    public BoothEntity booth;
    public BoothEntity otherBooth;
    public TableEntity table;
    public TableEntity otherBoothTable;
    public Long kimchiId;      // 8000
    public Long colaId;        // 5000
    public Long otherBoothMenuId;
    public StaffAccountEntity staff;
    public String staffToken;
    public String secondStaffToken;
    public String adminToken;
    public String otherBoothToken;
    public String superAdminToken;

    public PosTestFixture(BoothRepository boothRepository, StaffAccountRepository staffAccountRepository,
            MenuRepository menuRepository, TableRepository tableRepository,
            TableSessionRepository tableSessionRepository, OrderRepository orderRepository,
            DailyCounterRepository dailyCounterRepository, StaffCallRepository staffCallRepository,
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
        this.jwtProvider = jwtProvider;
        this.manualOrderService = manualOrderService;
        this.orderCreateService = orderCreateService;
    }

    public void setUp() {
        cleanUp();
        booth = boothRepository.save(new BoothEntity("POS 부스", "카카오뱅크 1234", null));
        otherBooth = boothRepository.save(new BoothEntity("남의 부스", "국민은행 5678", null));
        table = tableRepository.save(new TableEntity(booth, "A-3", "pos-token-a3"));
        otherBoothTable = tableRepository.save(new TableEntity(otherBooth, "A-3", "pos-token-other"));
        kimchiId = menuRepository.save(new MenuEntity(booth, "김치전", 8000, null, null, true)).getId();
        colaId = menuRepository.save(new MenuEntity(booth, "제로콜라", 5000, null, null, true)).getId();
        otherBoothMenuId = menuRepository.save(new MenuEntity(otherBooth, "남의 메뉴", 1000, null, null, true)).getId();

        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        LocalDateTime pwdAt = LocalDateTime.of(2026, 9, 1, 12, 0);
        staff = staffAccountRepository.save(new StaffAccountEntity(booth, "pos-staff", hash, pwdAt, StaffRole.STAFF));
        staffToken = jwtProvider.issue(staff, Instant.now());
        secondStaffToken = issue(new StaffAccountEntity(booth, "pos-staff-2", hash, pwdAt, StaffRole.STAFF));
        adminToken = issue(new StaffAccountEntity(booth, "pos-admin", hash, pwdAt, StaffRole.ADMIN));
        otherBoothToken = issue(new StaffAccountEntity(otherBooth, "pos-other", hash, pwdAt, StaffRole.ADMIN));
        superAdminToken = issue(new StaffAccountEntity(null, "pos-super", hash, pwdAt, StaffRole.SUPER_ADMIN));
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

    /** 테이블 지정 수기 주문(O14) — 활성 세션이 없으면 만들고 붙는다 */
    public OrderCreateResponse manualOrder(TableEntity target, Long menuId, int qty) {
        return manualOrderService.create(bearer(staffToken), null, new ManualOrderRequest(target.getId(),
                List.of(new OrderCreateRequest.OrderItemRequest(menuId, qty)))).response();
    }

    public OrderCreateResponse manualOrder(TableEntity target, List<OrderCreateRequest.OrderItemRequest> items) {
        return manualOrderService.create(bearer(staffToken), null, new ManualOrderRequest(target.getId(), items)).response();
    }

    /** 남의 부스 운영자가 남의 테이블에 넣는 수기 주문 — 교차 부스 테스트의 "B 부스 주문" 재료 */
    public OrderCreateResponse otherBoothManualOrder() {
        return manualOrderService.create(bearer(otherBoothToken), null, new ManualOrderRequest(otherBoothTable.getId(),
                List.of(new OrderCreateRequest.OrderItemRequest(otherBoothMenuId, 1)))).response();
    }

    /** 손님 주문(C3) — 인증 계층을 통과했다고 보고 활성 세션 id로 바로 넣는다 */
    public OrderCreateResponse customerOrder(Long menuId, int qty) {
        TableSessionEntity session = activeSession();
        return orderCreateService.create(booth.getId(), session.getId(), table.getLabel(), UUID.randomUUID().toString(),
                new OrderCreateRequest(List.of(new OrderCreateRequest.OrderItemRequest(menuId, qty)))).response();
    }

    /**
     * 손님 주문(C3) + 즉시 승인(O28) — O24 일괄 결제·O12 완료·C5 취소 등 RECEIVED를 전제로 하는 경합 대상 주문용
     * (Figma "주문현황-승인대기" 641:1362는 승인/거절만 허용, 결제·완료·취소 대상이 아니다)
     */
    public OrderCreateResponse approvedCustomerOrder(Long menuId, int qty) {
        OrderCreateResponse response = customerOrder(menuId, qty);
        orderRepository.approve(response.orderId(), booth.getId());
        return response;
    }

    public TableSessionEntity activeSession() {
        return tableSessionRepository.findByTableIdAndEndedAtIsNull(table.getId()).orElseThrow();
    }

    public static OrderCreateRequest.OrderItemRequest item(Long menuId, int qty) {
        return new OrderCreateRequest.OrderItemRequest(menuId, qty);
    }
}
