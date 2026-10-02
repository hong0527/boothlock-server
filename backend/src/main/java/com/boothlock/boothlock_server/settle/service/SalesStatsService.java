package com.boothlock.boothlock_server.settle.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemType;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;
import com.boothlock.boothlock_server.settle.dto.SalesStatsResponse;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        // 정산은 그날 전체 합계라 대시보드용 30건 제한을 걸면 안 됨 — 명시적으로 무제한.
        // excludeHidden=false — 삭제(hidden=true) 처리된 취소 주문도 환불필요·환불완료 집계에서 빠지면 안 되므로
        // 대시보드(DashboardQueryService)와 달리 hidden 여부와 무관하게 전부 조회한다.
        List<OrderEntity> orders = orderRepository.searchForDashboard(
                booth.getId(), null, null, businessDate, null, null, false, false, Limit.unlimited());

        long totalSales = 0;
        long paidOrderCount = 0;
        long bankTransferSales = 0;
        long cashSales = 0;
        long refundNeededCount = 0;
        long refundNeededAmount = 0;
        long refundedCount = 0;
        long refundedAmount = 0;
        // 종류+이름으로 묶는다(정산 엑셀 요약처럼 이름 기준) — 행사 중 메뉴를 지우거나 단가를 바꿔도 판매분은 남는다
        Map<ItemKey, long[]> qtyAndAmountByItem = new LinkedHashMap<>();

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
                for (OrderItemEntity item : order.getItems()) {
                    if (item.isCanceled()) {
                        continue;
                    }
                    long[] qtyAndAmount = qtyAndAmountByItem.computeIfAbsent(
                            new ItemKey(item.getItemType(), item.getMenuName()), k -> new long[2]);
                    qtyAndAmount[0] += item.getQty();
                    qtyAndAmount[1] += (long) item.getUnitPrice() * item.getQty();
                }
            } else if (paymentStatus == PaymentStatus.REFUND_NEEDED) {
                refundNeededCount++;
                refundNeededAmount += amount;
            } else if (paymentStatus == PaymentStatus.REFUNDED) {
                refundedCount++;
                refundedAmount += amount;
            }
        }

        // 종류(MENU→SEAT_FEE→EXTRA) 안에서 많이 팔린 순 — 같으면 이름 순으로 고정해 새로고침마다 순서가 흔들리지 않게 한다
        List<SalesStatsResponse.ItemSales> itemSales = qtyAndAmountByItem.entrySet().stream()
                .map(e -> new SalesStatsResponse.ItemSales(
                        e.getKey().itemType(), e.getKey().name(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparing(SalesStatsResponse.ItemSales::itemType)
                        .thenComparing(Comparator.comparingLong(SalesStatsResponse.ItemSales::qty).reversed())
                        .thenComparing(SalesStatsResponse.ItemSales::name))
                .toList();

        return new SalesStatsResponse(
                businessDate.toString(),
                totalSales,
                new SalesStatsResponse.ByMethod(bankTransferSales, cashSales),
                paidOrderCount,
                new SalesStatsResponse.RefundSummary(refundNeededCount, refundNeededAmount),
                new SalesStatsResponse.RefundSummary(refundedCount, refundedAmount),
                itemSales);
    }

    private record ItemKey(OrderItemType itemType, String name) {
    }
}
