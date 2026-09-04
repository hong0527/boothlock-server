package com.boothlock.boothlock_server.settle.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;
import com.boothlock.boothlock_server.settle.dto.SalesStatsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class SalesStatsService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final OrderRepository orderRepository;
    private final OrderNumberingService orderNumberingService;

    public SalesStatsService(
            BoothJwtProvider jwtProvider,
            BoothInfoService boothInfoService,
            OrderRepository orderRepository,
            OrderNumberingService orderNumberingService) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.orderRepository = orderRepository;
        this.orderNumberingService = orderNumberingService;
    }

    @Transactional(readOnly = true)
    public SalesStatsResponse getSales(String authorization, LocalDate requestedDate) {
        StaffAccountEntity staff = boothInfoService.authenticate(jwtProvider.verify(authorization));
        BoothEntity booth = staff.getBooth();
        if (booth == null) {
            throw new ForbiddenException();
        }
        if (staff.getRole() != StaffRole.ADMIN) {
            throw new ForbiddenException();
        }

        LocalDate businessDate = requestedDate != null
                ? requestedDate
                : orderNumberingService.businessDateOf(LocalDateTime.now(KST));
        List<OrderEntity> orders = orderRepository.searchForDashboard(
                booth.getId(), null, null, businessDate, null);

        long totalSales = 0;
        long paidOrderCount = 0;
        long bankTransferSales = 0;
        long cashSales = 0;
        long refundNeededCount = 0;
        long refundNeededAmount = 0;
        long refundedCount = 0;
        long refundedAmount = 0;

        for (OrderEntity order : orders) {
            long amount = order.getTotalAmount();
            PaymentStatus paymentStatus = order.getPaymentStatus();

            if (paymentStatus == PaymentStatus.PAID) {
                totalSales += amount;
                paidOrderCount++;
                if (order.getPaymentMethod() == PaymentMethod.BANK_TRANSFER) {
                    bankTransferSales += amount;
                } else if (order.getPaymentMethod() == PaymentMethod.CASH) {
                    cashSales += amount;
                }
            } else if (paymentStatus == PaymentStatus.REFUND_NEEDED) {
                refundNeededCount++;
                refundNeededAmount += amount;
            } else if (paymentStatus == PaymentStatus.REFUNDED) {
                refundedCount++;
                refundedAmount += amount;
            }
        }

        return new SalesStatsResponse(
                totalSales,
                new SalesStatsResponse.ByMethod(bankTransferSales, cashSales),
                paidOrderCount,
                new SalesStatsResponse.RefundSummary(refundNeededCount, refundNeededAmount),
                new SalesStatsResponse.RefundSummary(refundedCount, refundedAmount));
    }
}
