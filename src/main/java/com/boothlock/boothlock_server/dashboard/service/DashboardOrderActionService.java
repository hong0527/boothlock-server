package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.dashboard.dto.DashboardResponse;
import com.boothlock.boothlock_server.global.error.AlreadyPaidException;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** O11 입금 확인·O12 완료 처리 — 운영자 인증 후 주문 상태 전이 (명세서 O11·O12) */
@Service
public class DashboardOrderActionService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final OrderRepository orderRepository;
    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final OrderSummaryMapper mapper;

    public DashboardOrderActionService(OrderRepository orderRepository, BoothJwtProvider jwtProvider,
            BoothInfoService boothInfoService, OrderSummaryMapper mapper) {
        this.orderRepository = orderRepository;
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.mapper = mapper;
    }

    /** O11 입금 확인 — UNPAID→PAID, 승인자·승인시각 자동 기록 (명세서 O11) */
    @Transactional
    public DashboardResponse.OrderSummary confirmPayment(String authorization, Long orderId, PaymentMethod method) {
        StaffAccountEntity staff = authenticate(authorization);
        Long boothId = staff.getBooth().getId();
        LocalDateTime now = LocalDateTime.now(KST);

        int updated = orderRepository.markPaid(orderId, boothId, method, staff.getLoginId(), now);
        if (updated == 0) {
            OrderEntity existing = requireExisting(orderId, boothId);   // 없으면 여기서 404
            if (existing.getStatus() == OrderStatus.CANCELED) {
                throw new InvalidStateException("취소된 주문은 입금 확인할 수 없습니다.");
            }
            throw new AlreadyPaidException();
        }
        return mapper.toOrderSummary(requireExisting(orderId, boothId));
    }

    /** O12 완료 처리 — RECEIVED→DONE. 결제 여부는 상관하지 않는다 (명세서 O12) */
    @Transactional
    public DashboardResponse.OrderSummary complete(String authorization, Long orderId) {
        StaffAccountEntity staff = authenticate(authorization);
        Long boothId = staff.getBooth().getId();

        int updated = orderRepository.markDone(orderId, boothId);
        if (updated == 0) {
            requireExisting(orderId, boothId);   // 없으면 여기서 404, 있으면 RECEIVED가 아닌 상태라 409
            throw new InvalidStateException("완료 처리할 수 없는 주문 상태입니다.");
        }
        return mapper.toOrderSummary(requireExisting(orderId, boothId));
    }

    private StaffAccountEntity authenticate(String authorization) {
        StaffAccountEntity staff = boothInfoService.authenticate(jwtProvider.verify(authorization));
        if (staff.getBooth() == null) {
            throw new ForbiddenException();
        }
        return staff;
    }

    // booth 범위로 스코프해 타 부스 주문은 조회 단계에서 404가 되게 한다 (존재 은닉)
    private OrderEntity requireExisting(Long orderId, Long boothId) {
        return orderRepository.findByIdAndBoothId(orderId, boothId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다."));
    }
}
