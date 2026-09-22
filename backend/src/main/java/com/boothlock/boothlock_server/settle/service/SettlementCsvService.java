package com.boothlock.boothlock_server.settle.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * O19 정산 CSV (기능 7.3). 명세서 규칙 그대로: {@code text/csv; charset=UTF-8} BOM 포함,
 * 수식 주입 방지(= + - @로 시작하는 셀은 앞에 ' 붙임) + 표준 CSV 이스케이프.
 *
 * <p>조회 기준은 "영업일 전체"가 아니라 사용자가 직접 지정하는 [startAt, endAt) 구간이다 (팀 결정).
 * <ul>
 *   <li><b>구간 기준</b> — 주문 생성 시각(createdAt). {@code start <= createdAt < end}. 자정을 넘기는 구간
 *       (예 2026-09-30 22:00~2026-10-01 03:00)도 그대로 처리된다 — 영업일(06:00 경계) 개념과 무관.</li>
 *   <li><b>대상 = 전체 원장</b> — {@code paymentStatus}로 행을 거르지 않는다. UNPAID·PAID·REFUND_NEEDED·
 *       REFUNDED 주문 모두 나온다(개별 취소된 OrderItem만 뺀다) — "결제완료된 것만 보여주는 리포트"가 아니라
 *       그 시간대에 실제로 무슨 주문이 있었는지 확인하는 원장이기 때문이다.</li>
 *   <li><b>총 결제완료 매출액</b> — 상세 행 전체와는 별개로, 상세 행에 실제 출력된(개별 취소 제외) 항목 중
 *       {@code paymentStatus = PAID}인 것만 골라 {@code unitPrice × qty}를 합산한 값이다.
 *       {@link OrderEntity#getTotalAmount()}는 쓰지 않고, 상세 행 계산에 쓴 같은 amount 값을 재사용하므로
 *       중복 합산이나 상세-총액 불일치가 생기지 않는다. 환불액·순매출과는 무관하다.</li>
 * </ul>
 */
@Service
public class SettlementCsvService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILENAME_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final String[] HEADERS = {
            "주문번호", "테이블", "주문시각", "메뉴명", "수량", "단가", "금액", "주문상태", "결제상태", "결제수단",
            "승인자", "승인시각", "취소자", "취소시각", "취소사유", "환불처리자", "환불처리시각",
    };

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final OrderRepository orderRepository;

    public SettlementCsvService(BoothJwtProvider jwtProvider, BoothInfoService boothInfoService,
            OrderRepository orderRepository) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.orderRepository = orderRepository;
    }

    public record Result(Long boothId, LocalDateTime startAt, LocalDateTime endAt, byte[] content) {
        public String filename() {
            return "settlement_" + boothId + "_" + startAt.format(FILENAME_TIMESTAMP_FORMAT)
                    + "_" + endAt.format(FILENAME_TIMESTAMP_FORMAT) + ".csv";
        }
    }

    @Transactional(readOnly = true)
    public Result generate(String authorization, LocalDateTime startAt, LocalDateTime endAt) {
        StaffAccountEntity staff = boothInfoService.authenticate(jwtProvider.verify(authorization));
        BoothEntity booth = staff.getBooth();
        if (booth == null || staff.getRole() != StaffRole.ADMIN) {
            throw new ForbiddenException();
        }
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new InvalidRequestException("시작 일시(startAt)는 마감 일시(endAt)보다 이전이어야 합니다.");
        }

        List<OrderEntity> orders = orderRepository.searchForSettlementRange(booth.getId(), startAt, endAt);

        StringBuilder csv = new StringBuilder();
        csv.append((char) 0xFEFF); // Excel이 UTF-8로 열도록 BOM(제어 문자를 직접 코드포인트로 써서 소스에 보이지 않는 문자가 섞이지 않게)
        appendRow(csv, HEADERS);
        long totalPaid = 0L;
        for (OrderEntity order : orders) {
            for (OrderItemEntity item : order.getItems()) {
                if (item.isCanceled()) {
                    continue;
                }
                long amount = (long) item.getUnitPrice() * item.getQty();
                appendRow(csv, row(order, item, amount));
                if (order.getPaymentStatus() == PaymentStatus.PAID) {
                    totalPaid += amount;
                }
            }
        }

        // 상세 데이터와 요약 영역을 빈 줄 하나로 구분
        csv.append("\r\n");
        appendRow(csv, new String[] {"구분", "금액"});
        appendRow(csv, new String[] {"총 결제완료 매출액", String.valueOf(totalPaid)});

        return new Result(booth.getId(), startAt, endAt, csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String[] row(OrderEntity order, OrderItemEntity item, long amount) {
        return new String[] {
                order.getOrderNo(),
                emptyIfNull(order.getTableLabel()),
                formatTime(order.getCreatedAt()),
                item.getMenuName(),
                String.valueOf(item.getQty()),
                String.valueOf(item.getUnitPrice()),
                String.valueOf(amount),
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
