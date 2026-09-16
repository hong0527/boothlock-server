package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.dashboard.dto.TablePaymentRequest;
import com.boothlock.boothlock_server.dashboard.dto.TablePaymentResponse;
import com.boothlock.boothlock_server.dashboard.repository.TablePaymentOrderRepository;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * O24 테이블 일괄 입금 확인 — 그 테이블의 활성 세션 미결제 주문 전부를 한 트랜잭션에서 O11과 같은 조건부 UPDATE로 확인한다.
 * 대상 주문을 id 순으로 잠근 뒤 합계를 다시 계산해 운영자가 본 금액(expectedTotal)과 맞을 때만 진행한다.
 * 한 건이라도 조건에 실패하면 예외로 전체를 롤백한다 — 테이블 결제가 일부만 확인된 상태로 남지 않게.
 */
@Service
public class TablePaymentService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final BoothStaffAuthenticator staffAuthenticator;
    private final BoothTableLookup tableLookup;
    private final TablePaymentOrderRepository targetRepository;
    private final OrderRepository orderRepository;
    private final OrderSummaryMapper mapper;

    public TablePaymentService(BoothStaffAuthenticator staffAuthenticator, BoothTableLookup tableLookup,
            TablePaymentOrderRepository targetRepository, OrderRepository orderRepository, OrderSummaryMapper mapper) {
        this.staffAuthenticator = staffAuthenticator;
        this.tableLookup = tableLookup;
        this.targetRepository = targetRepository;
        this.orderRepository = orderRepository;
        this.mapper = mapper;
    }

    @Transactional
    public TablePaymentResponse confirmTablePayment(String authorization, TablePaymentRequest request) {
        StaffAccountEntity staff = staffAuthenticator.authenticate(authorization);
        if (request == null || request.tableId() == null || request.method() == null
                || request.expectedTotal() == null || request.expectedTotal() < 0) {
            throw new InvalidRequestException("tableId·method·expectedTotal(0 이상)이 필요합니다.");
        }
        Long boothId = staff.getBooth().getId();
        tableLookup.requireTableOfBooth(request.tableId(), boothId);   // 타 부스·미존재·삭제 테이블 404

        List<OrderEntity> targets = targetRepository.findUnpaidOfActiveTableSessionsForUpdate(boothId, request.tableId());
        if (targets.isEmpty()) {
            // 화면이 낡았다는 신호 — 200 빈 결과로 주면 운영자가 "결제 완료"로 착각할 수 있다
            throw new InvalidStateException("결제할 미결제 주문이 없습니다. 화면을 새로고침해주세요.");
        }
        int total = targets.stream().mapToInt(OrderEntity::getTotalAmount).sum();
        if (total != request.expectedTotal()) {
            throw new InvalidStateException("미결제 합계가 바뀌었습니다 (화면 " + request.expectedTotal()
                    + "원, 현재 " + total + "원). 새로고침 후 다시 확인해주세요.");
        }

        List<Long> orderIds = targets.stream().map(OrderEntity::getId).toList();
        LocalDateTime now = LocalDateTime.now(KST);
        for (Long orderId : orderIds) {
            // O11과 같은 조건부 UPDATE·승인자 기록 — 잠금 아래라 실패하지 않아야 하지만, 실패하면 전체 롤백
            if (orderRepository.markPaid(orderId, boothId, request.method(), staff.getLoginId(), now) != 1) {
                throw new InvalidStateException("입금 확인할 수 없는 주문이 섞여 있습니다. 새로고침 후 다시 확인해주세요.");
            }
        }

        List<DashboardResponse.OrderSummary> confirmed = orderIds.stream()
                .map(orderId -> orderRepository.findByIdAndBoothId(orderId, boothId)
                        .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다.")))
                .map(mapper::toOrderSummary)
                .toList();
        return new TablePaymentResponse(confirmed, total);
    }
}
