package com.boothlock.boothlock_server.settle.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * O19 정산 CSV (기능 7.3). 명세서 규칙 그대로: {@code text/csv; charset=UTF-8} BOM 포함,
 * 파일명 {@code settlement_{boothId}_{영업일}.csv}, 수식 주입 방지(= + - @로 시작하는 셀은 앞에 ' 붙임).
 *
 * <p>명세가 "구현 시 결정"으로 남겨둔 것들:
 * <ul>
 *   <li><b>취소 항목 포함 여부</b> — 개별 취소(O23b)된 항목은 뺀다. 다른 응답(C4·대시보드)도 취소 항목을 빼야
 *       {@code totalAmount}와 항목 합이 맞는다는 같은 원칙을 따른다 — 정산 총액도 이 규칙과 어긋나면 안 된다.</li>
 *   <li><b>권한 범위</b> — 명세 O19 자체엔 ADMIN 제한이 없지만, 같은 "7 정산" 범주인 O18(매출 집계)이
 *       ADMIN 전용이라 통일했다. 재무 데이터라는 성격도 같은 방향. PR에서 확인 필요.</li>
 * </ul>
 */
@Service
public class SettlementCsvService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] HEADERS = {
            "주문번호", "테이블", "주문시각", "메뉴명", "수량", "단가", "금액", "주문상태", "결제상태", "결제수단",
            "승인자", "승인시각", "취소자", "취소시각", "취소사유", "환불처리자", "환불처리시각",
    };

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final OrderRepository orderRepository;
    private final OrderNumberingService orderNumberingService;

    public SettlementCsvService(BoothJwtProvider jwtProvider, BoothInfoService boothInfoService,
            OrderRepository orderRepository, OrderNumberingService orderNumberingService) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.orderRepository = orderRepository;
        this.orderNumberingService = orderNumberingService;
    }

    public record Result(Long boothId, LocalDate businessDate, byte[] content) {
        public String filename() {
            return "settlement_" + boothId + "_" + businessDate + ".csv";
        }
    }

    @Transactional(readOnly = true)
    public Result generate(String authorization, LocalDate requestedDate) {
        StaffAccountEntity staff = boothInfoService.authenticate(jwtProvider.verify(authorization));
        BoothEntity booth = staff.getBooth();
        if (booth == null || staff.getRole() != StaffRole.ADMIN) {
            throw new ForbiddenException();
        }

        LocalDate businessDate = requestedDate != null
                ? requestedDate
                : orderNumberingService.businessDateOf(LocalDateTime.now(KST));

        // O18과 같은 호출(hidden 무관 전부 조회) — 정산은 삭제(hide) 처리된 취소 주문도 원장에서 빠지면 안 된다
        List<OrderEntity> orders = orderRepository.searchForDashboard(
                booth.getId(), null, null, businessDate, null, null, false, false, Limit.unlimited());

        StringBuilder csv = new StringBuilder();
        csv.append((char) 0xFEFF); // Excel이 UTF-8로 열도록 BOM(제어 문자를 직접 코드포인트로 써서 소스에 보이지 않는 문자가 섮이지 않게)
        appendRow(csv, HEADERS);
        for (OrderEntity order : orders) {
            for (OrderItemEntity item : order.getItems()) {
                if (item.isCanceled()) {
                    continue;
                }
                appendRow(csv, row(order, item));
            }
        }

        return new Result(booth.getId(), businessDate, csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String[] row(OrderEntity order, OrderItemEntity item) {
        return new String[] {
                order.getOrderNo(),
                emptyIfNull(order.getTableLabel()),
                formatTime(order.getCreatedAt()),
                item.getMenuName(),
                String.valueOf(item.getQty()),
                String.valueOf(item.getUnitPrice()),
                String.valueOf(item.getUnitPrice() * item.getQty()),
                order.getStatus().name(),
                order.getPaymentStatus().name(),
                order.getPaymentMethod() == null ? "" : order.getPaymentMethod().name(),
                emptyIfNull(order.getApprovedBy()),
                formatTime(order.getApprovedAt()),
                emptyIfNull(order.getCanceledBy()),
                formatTime(order.getCanceledAt()),
                emptyIfNull(order.getCancelReason()),
                emptyIfNull(order.getRefundedBy()),
                formatTime(order.getRefundedAt()),
        };
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(TIMESTAMP_FORMAT);
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private void appendRow(StringBuilder csv, String[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escapeCsv(cells[i]));
        }
        csv.append("\r\n");
    }

    /** 수식 주입 방지(= + - @로 시작하면 앞에 ' 붙임) 후 CSV 표준 이스케이프(쉼표·따옴표·개행 포함 시 큰따옴표로 감싸기) */
    private String escapeCsv(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
