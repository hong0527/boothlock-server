package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.global.error.AlreadyPaidException;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderCreateService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** O11 입금 확인·O12 완료 처리 — 운영자 인증 후 주문 상태 전이 (명세서 O11·O12) */
@Service
public class DashboardOrderActionService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    // 프론트 디자인에 취소 사유 입력 UI가 없어서, 비어있으면 이 값으로 기록한다
    private static final String DEFAULT_CANCEL_REASON = "운영자 취소";
    /** 결제 모달에서 마지막 항목까지 취소해 주문 전체가 취소될 때 남기는 사유 (cancel_reason VARCHAR(100)) */
    static final String LAST_ITEM_CANCEL_REASON = "전체 항목 취소";

    private final OrderRepository orderRepository;
    private final BoothStaffAuthenticator staffAuthenticator;
    private final OrderSummaryMapper mapper;
    private final OrderCreateService orderCreateService;

    public DashboardOrderActionService(OrderRepository orderRepository, BoothStaffAuthenticator staffAuthenticator,
            OrderSummaryMapper mapper, OrderCreateService orderCreateService) {
        this.orderRepository = orderRepository;
        this.staffAuthenticator = staffAuthenticator;
        this.mapper = mapper;
        this.orderCreateService = orderCreateService;
    }

    /** O11 입금 확인 — UNPAID→PAID, 승인자·승인시각 자동 기록 (명세서 O11) */
    @Transactional
    public DashboardResponse.OrderSummary confirmPayment(String authorization, Long orderId, PaymentMethod method) {
        StaffAccountEntity staff = authenticate(authorization);
        Long boothId = staff.getBooth().getId();
        LocalDateTime now = LocalDateTime.now(KST);

        int updated = orderRepository.markPaid(orderId, boothId, method, staff.getLoginId(), now);
        if (updated == 0) {
            // 잠금 조회로 다시 읽는다 — 일반 조회는 MySQL REPEATABLE READ에서 인증 시점 스냅샷(아직 RECEIVED·UNPAID)을 돌려줘
            // 방금 커밋된 취소를 못 보고 409 사유를 ALREADY_PAID로 잘못 고른다(MySQL 8.4 실측). 없으면 여기서 404
            OrderEntity existing = requireExistingForUpdate(orderId, boothId);
            if (existing.getStatus() == OrderStatus.CANCELED) {
                throw new InvalidStateException("취소된 주문은 입금 확인할 수 없습니다.");
            }
            throw new AlreadyPaidException();
        }
        return mapper.toOrderSummary(requireExistingForUpdate(orderId, boothId));
    }

    /** O12 완료 처리 — RECEIVED→DONE. 결제 여부는 상관하지 않는다 (명세서 O12) */
    @Transactional
    public DashboardResponse.OrderSummary complete(String authorization, Long orderId) {
        StaffAccountEntity staff = authenticate(authorization);
        Long boothId = staff.getBooth().getId();

        int updated = orderRepository.markDone(orderId, boothId);
        if (updated == 0) {
            requireExistingForUpdate(orderId, boothId);   // 없으면 여기서 404, 있으면 RECEIVED가 아닌 상태라 409
            throw new InvalidStateException("완료 처리할 수 없는 주문 상태입니다.");
        }
        return mapper.toOrderSummary(requireExistingForUpdate(orderId, boothId));
    }

    /** O13 운영자 취소 — 전 단계 가능(이미 취소된 것 제외), PAID였으면 REFUND_NEEDED로 함께 전환 (명세서 O13) */
    @Transactional
    public DashboardResponse.OrderSummary cancelByStaff(String authorization, Long orderId, String reason) {
        StaffAccountEntity staff = authenticate(authorization);
        Long boothId = staff.getBooth().getId();
        LocalDateTime now = LocalDateTime.now(KST);
        String recordedReason = (reason == null || reason.isBlank()) ? DEFAULT_CANCEL_REASON : reason;

        int updated = orderRepository.cancelByStaff(orderId, boothId, recordedReason, staff.getLoginId(), now);
        if (updated == 0) {
            requireExistingForUpdate(orderId, boothId);   // 없으면 여기서 404, 있으면 이미 취소된 주문이라 409
            throw new InvalidStateException("이미 취소된 주문입니다.");
        }
        return mapper.toOrderSummary(requireExistingForUpdate(orderId, boothId));
    }

    /**
     * O6 결제 모달 수량 +/- — RECEIVED+UNPAID일 때만, 아니면 409. 주문 행을 FOR UPDATE로 잠근 채 상태 판정→수량 변경→합계
     * 재계산을 한 트랜잭션에서 끝낸다. 입금 확인(O11)의 조건부 UPDATE도 같은 행 잠금을 기다리므로, 입금이 먼저 커밋되면
     * 여기서 PAID를 보고 409, 여기가 먼저면 입금 확인은 바뀐 금액의 주문을 본다 (audit2 B1 — 승인 금액 ≠ 저장 금액 방지).
     * 증가는 추가 주문과 같아서 그 메뉴의 품절·숨김·부스 마감을 C3와 같은 기준으로 검사한다 (audit2 H1)
     */
    @Transactional
    public DashboardResponse.OrderSummary updateItemQty(String authorization, Long orderId, Long itemId, int qty) {
        StaffAccountEntity staff = authenticate(authorization);
        Long boothId = staff.getBooth().getId();
        OrderEntity order = requireExistingForUpdate(orderId, boothId);
        if (!order.canEditItems()) {
            throw new InvalidStateException("항목을 수정할 수 없는 주문 상태입니다.");
        }
        OrderItemEntity item = order.requireEditableItem(itemId);   // 이 주문의 살아 있는 항목이 아니면 404
        OrderItemEntity.requireValidQty(qty);                       // 1~30 밖이면 품절 검사 전에 400
        if (qty > item.getQty()) {
            orderCreateService.ensureOrderable(boothId, item.getMenuId());   // 마감 409 ORDER_CLOSED, 숨김·품절 409 SOLD_OUT
        }
        order.updateItemQty(itemId, qty);
        return mapper.toOrderSummary(order);
    }

    /**
     * O6 결제 모달 개별 "취소" — 항목은 숨김(canceled=true)으로 남기고 합계를 다시 계산한다. 잠금 규칙은 updateItemQty와 같다.
     * 남은 항목이 없으면 O13 운영자 취소와 같은 조건부 UPDATE(cancelByStaff)로 주문을 CANCELED로 넘기고 취소자·시각·사유를
     * 기록한다 — 같은 주문의 두 항목 동시 취소(audit2 B2)도 행 잠금으로 직렬화되고, C5·O13과 겹쳐도 취소 기록을 덮어쓰지 않는다(M6)
     */
    @Transactional
    public DashboardResponse.OrderSummary cancelItem(String authorization, Long orderId, Long itemId) {
        StaffAccountEntity staff = authenticate(authorization);
        Long boothId = staff.getBooth().getId();
        OrderEntity order = requireExistingForUpdate(orderId, boothId);
        boolean lastItem = order.cancelItem(itemId);   // 상태 밖이면 409, 항목 없으면 404
        if (!lastItem) {
            return mapper.toOrderSummary(order);
        }

        // 항목 숨김·합계 0을 먼저 DB에 반영한다 — 아래 벌크 UPDATE가 영속성 컨텍스트를 비우므로(clearAutomatically)
        // 미반영 변경은 사라진다. 자동 flush에 기대지 않고 명시한다
        orderRepository.flush();
        int updated = orderRepository.cancelByStaff(
                orderId, boothId, LAST_ITEM_CANCEL_REASON, staff.getLoginId(), LocalDateTime.now(KST));
        if (updated == 0) {
            throw new InvalidStateException("이미 취소된 주문입니다.");   // 행 잠금 아래라 도달하지 않는다 — 방어
        }
        return mapper.toOrderSummary(requireExistingForUpdate(orderId, boothId));
    }

    /** O21 환불 완료 — ADMIN 전용, REFUND_NEEDED만 REFUNDED로 전환 (명세서 O21) */
    @Transactional
    public DashboardResponse.OrderSummary refundDone(String authorization, Long orderId) {
        StaffAccountEntity staff = authenticate(authorization);
        if (staff.getRole() != StaffRole.ADMIN) {
            throw new ForbiddenException();
        }
        Long boothId = staff.getBooth().getId();
        LocalDateTime now = LocalDateTime.now(KST);

        int updated = orderRepository.markRefunded(orderId, boothId, staff.getLoginId(), now);
        if (updated == 0) {
            requireExistingForUpdate(orderId, boothId);   // 없으면 여기서 404, 있으면 REFUND_NEEDED가 아니라 409
            throw new InvalidStateException("환불 대상 주문이 아닙니다.");
        }
        return mapper.toOrderSummary(requireExistingForUpdate(orderId, boothId));
    }

    /** 대시보드 공용 인증기 — 무토큰·위조 401, SUPER_ADMIN 403, boothId 클레임이 계정 현재 부스와 다르면 401 */
    private StaffAccountEntity authenticate(String authorization) {
        return staffAuthenticator.authenticate(authorization);
    }

    /**
     * booth 범위로 스코프해 타 부스 주문은 조회 단계에서 404가 되게 한다 (존재 은닉). 주문 행과 항목을 모두 잠금 읽기(FOR UPDATE)로 읽어
     * 격리수준과 무관하게 최신 커밋을 보게 한다. 판정 뒤 수정하는 경로(항목 수량·개별 취소)의 첫 읽기, 그리고 조건부 UPDATE
     * (O11·O12·O13·O21) 뒤의 재조회가 모두 이걸 쓴다.
     *
     * <p>이 서비스의 트랜잭션은 인증 SELECT로 시작해 MySQL REPEATABLE READ에서는 그 시점 스냅샷이 끝까지 유지된다. 일반 조회·컬렉션
     * 지연 로딩으로 다시 읽으면 상대 트랜잭션이 잠금을 쥔 채 커밋한 변경(항목 취소·주문 취소)이 보이지 않아 409 사유·마지막 항목 판정·응답이 어긋난다.
     * 두 단계로 나눈다: ① 주문 행을 FOR UPDATE로 잠그고 최신 상태를 읽는다(단일 테이블이라 Hibernate가 for update를 유지). ② 항목을
     * 잠금 읽기로 최신 커밋까지 영속성 컨텍스트에 올린다({@link OrderRepository#lockItemsByOrderId} — join fetch는 for update가 떼여 스냅샷을 읽으므로 쓰지 않는다).
     * 이후 {@code order.getItems()}는 ②가 올린 최신 사본을 쓴다. 조건부 UPDATE 뒤에는 그 UPDATE가 이미 주문 행을 잠갔으므로 ①의 추가 대기는 없다
     */
    private OrderEntity requireExistingForUpdate(Long orderId, Long boothId) {
        OrderEntity order = orderRepository.findByIdAndBoothIdForUpdate(orderId, boothId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다."));
        orderRepository.lockItemsByOrderId(orderId);   // 최신 항목을 영속성 컨텍스트에 올린다 (반환값은 쓰지 않는다)
        return order;
    }
}
