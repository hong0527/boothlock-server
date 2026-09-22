package com.boothlock.boothlock_server.settle.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
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
 * <p>v0.6.5부터 조회 기준이 "영업일 전체 원장"에서 "지정 구간의 확정 매출"로 바뀌었다 (팀 결정).
 * <ul>
 *   <li><b>대상</b> — {@code paymentStatus = PAID}인 주문만. 취소·미입금 주문은 아예 조회되지 않는다
 *       (기존처럼 "취소 안 된 항목 전체"를 보여주는 원장이 아니라 확정 매출만 보여주는 리포트).</li>
 *   <li><b>구간 기준</b> — 주문 생성 시각(createdAt)이 아니라 입금 확인 시각
 *       ({@link OrderEntity#getApprovedAt()}, O11·O24가 PAID 전환과 같은 UPDATE 문으로 원자적으로 기록)이다.
 *       createdAt은 CSV의 "주문시각" 컬럼 표시용으로만 쓰고 조회 조건에는 쓰지 않는다.</li>
 *   <li><b>경계</b> — start &lt;= approvedAt &lt; end. 자정을 넘기는 구간(예 2026-09-30 22:00~2026-10-01 03:00)도
 *       approvedAt 비교만으로 그대로 처리된다 — 영업일(06:00 경계) 개념과 무관.</li>
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

        List<OrderEntity> orders = orderRepository.searchPaidForSettlementRange(booth.getId(), startAt, endAt);

        StringBuilder csv = new StringBuilder();
        csv.append((char) 0xFEFF); // Excel이 UTF-8로 열도록 BOM(제어 문자를 직접 코드포인트로 써서 소스에 보이지 않는 문자가 섞이지 않게)
        appendRow(csv, HEADERS);
        for (OrderEntity order : orders) {
            for (OrderItemEntity item : order.getItems()) {
                if (item.isCanceled()) {
                    continue;
                }
                appendRow(csv, row(order, item));
            }
        }

        return new Result(booth.getId(), startAt, endAt, csv.toString().getBytes(StandardCharsets.UTF_8));
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
