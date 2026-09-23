package com.boothlock.boothlock_server.settle.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O19 정산 엑셀 (기능 7.3). 운영자가 행사 마감 후 매출을 확인하고 계좌 거래내역과 대사하는 파일.
 *
 * <p><b>왜 CSV가 아니라 xlsx인가</b> — 열 너비·서식은 통합문서 안에만 저장할 수 있어서 CSV로는 지정할 방법이
 * 없다. CSV로 열면 날짜 열이 기본 너비(약 8자)보다 길어 엑셀이 {@code #######}로 표시하고, 운영자가 매번
 * 열 너비를 넓혀야 했다. xlsx로 바꾸면서 열 너비·굵은 머리글·천 단위 콤마·틀 고정까지 서버가 지정한다.
 *
 * <p>덤으로 수식 주입(= + - @로 시작하는 셀) 방어가 필요 없어졌다. CSV는 타입이 없어서 엑셀이 셀 내용을
 * 수식으로 해석했지만, xlsx는 셀마다 타입이 박히고 여기서는 문자열 셀로만 쓰기 때문에 엑셀이 실행하지 않는다.
 *
 * <p>조회 기준은 "영업일 전체"가 아니라 사용자가 직접 지정하는 [startAt, endAt) 구간이다 (팀 결정).
 * <ul>
 *   <li><b>구간 기준</b> — 주문 생성 시각(createdAt). {@code start <= createdAt < end}. 자정을 넘기는 구간
 *       (예 2026-09-30 22:00~2026-10-01 03:00)도 그대로 처리된다 — 영업일(06:00 경계) 개념과 무관.
 *       구간 폭은 {@link #MAX_RANGE_DAYS}일로 제한한다.</li>
 *   <li><b>대상 = 결제완료 매출</b> — 상세 행에는 {@code paymentStatus = PAID}인 주문의 항목만 나간다
 *       (개별 취소된 OrderItem은 제외). 운영자가 "이 시간대에 얼마 벌었나"를 보는 리포트이기 때문에
 *       미결제·환불 건으로 표를 채우지 않는다. 결제 상태와 무관한 전체 원장이 필요하면 O10 대시보드를 쓴다.</li>
 *   <li><b>시트 2장</b> — 먼저 열리는 "요약"에 메뉴별 판매수량·매출액과 총계를, "매출상세"에 주문 항목별
 *       원본 행을 넣는다. 두 시트의 금액은 같은 amount 계산을 재사용하므로 어긋날 수 없다.</li>
 *   <li><b>환불대기 금액</b> — {@code REFUND_NEEDED}(결제됐다가 취소됐지만 환불 송금은 아직인 건)는 매출이
 *       아니라서 상세와 총 매출액에서 빠진다. 다만 그 돈은 아직 운영자 손에 있어서, 빼고 끝내면 마감 후
 *       거래내역과 대사할 때 설명할 수 없는 차액이 남는다. 그래서 요약에 따로 적는다.</li>
 *   <li><b>결제수단 분리</b> — 대사 기준이 수단마다 다르다. 계좌이체분은 은행 거래내역과, 현금분은 실제
 *       현금통과 맞춰야 한다. 둘을 합친 숫자 하나만 주면 현금을 받은 부스에서는 그 값이 계좌 입금액과
 *       영영 맞지 않는다(O18 {@code SalesStatsService}가 수단별로 나누는 이유와 같다). 그래서 매출·환불대기
 *       모두 수단별로 쪼개고, 대사용 합계도 {@code 계좌 입금 합계}·{@code 현금 보유 합계}로 따로 낸다.</li>
 * </ul>
 */
@Service
public class SettlementReportService {

    public static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /**
     * 조회 구간 상한. 쿼리에 {@code Limit}이 없어서 구간을 넓게 잡으면 그 부스의 주문·항목이 전부 메모리로
     * 올라오고, 워크북도 통째로 메모리에서 조립된다. 파일럿 규모(2~3일 행사)에서 한 달을 넘겨 뽑을 일이
     * 없으므로 여기서 막는다 — 정산 파일 한 번 때문에 주문·결제 API까지 멈추지 않게.
     */
    private static final int MAX_RANGE_DAYS = 31;

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILENAME_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    static final String SUMMARY_SHEET = "요약";
    static final String DETAIL_SHEET = "매출상세";

    private static final String[] DETAIL_HEADERS = {
            "주문번호", "테이블", "주문시각", "메뉴명", "수량", "단가", "금액", "결제상태", "결제수단",
    };
    private static final String[] MENU_SUMMARY_HEADERS = {"메뉴명", "판매수량", "매출액"};

    /** 열 너비 — POI 단위는 1/256 문자폭. autoSizeColumn은 한글 폭을 제대로 못 재서 직접 지정한다. */
    private static final int[] DETAIL_WIDTHS = {12, 10, 22, 24, 8, 12, 14, 12, 14};
    private static final int[] SUMMARY_WIDTHS = {28, 12, 16};

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final OrderRepository orderRepository;

    public SettlementReportService(BoothJwtProvider jwtProvider, BoothInfoService boothInfoService,
            OrderRepository orderRepository) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.orderRepository = orderRepository;
    }

    public record Result(Long boothId, LocalDateTime startAt, LocalDateTime endAt, byte[] content) {
        public String filename() {
            return "settlement_" + boothId + "_" + startAt.format(FILENAME_TIMESTAMP_FORMAT)
                    + "_" + endAt.format(FILENAME_TIMESTAMP_FORMAT) + ".xlsx";
        }
    }

    /** 메뉴 하나의 판매 집계 — 같은 메뉴명끼리 묶는다. 행사 중 단가를 바꿨어도 판매수량·매출액은 그대로 합산된다. */
    private static final class MenuSales {
        private final String menuName;
        private long qty;
        private long amount;

        private MenuSales(String menuName) {
            this.menuName = menuName;
        }
    }

    /** 상세 시트에 그대로 쓸 한 줄. 워크북을 만들기 전에 집계를 끝내려고 따로 모아둔다. */
    private record DetailRow(String orderNo, String tableLabel, String createdAt, String menuName,
            int qty, int unitPrice, long amount, String paymentStatus, String paymentMethod) {
    }

    @Transactional(readOnly = true)
    public Result generate(String authorization, LocalDateTime startAt, LocalDateTime endAt) {
        StaffAccountEntity staff = boothInfoService.authenticate(jwtProvider.verify(authorization));
        BoothEntity booth = staff.getBooth();
        if (booth == null || staff.getRole() != StaffRole.ADMIN) {
            throw new ForbiddenException();
        }
        // startAt·endAt은 @RequestParam(required=true)라 여기까지 null로 내려오지 않는다 — 순서만 본다.
        if (!startAt.isBefore(endAt)) {
            throw new InvalidRequestException("시작 일시(startAt)는 마감 일시(endAt)보다 이전이어야 합니다.");
        }
        // Duration.toDays()는 31일 1초를 31로 내림해 상한을 넘겨보낸다 — 경계 시각과 직접 비교한다
        if (startAt.plusDays(MAX_RANGE_DAYS).isBefore(endAt)) {
            throw new InvalidRequestException("조회 구간은 최대 " + MAX_RANGE_DAYS + "일까지 지정할 수 있습니다.");
        }

        List<OrderEntity> orders = orderRepository.searchForSettlementRange(booth.getId(), startAt, endAt);

        List<DetailRow> details = new ArrayList<>();
        Map<String, MenuSales> salesByMenu = new LinkedHashMap<>();
        Totals totals = new Totals();
        for (OrderEntity order : orders) {
            boolean paid = order.getPaymentStatus() == PaymentStatus.PAID;
            boolean cash = order.getPaymentMethod() == PaymentMethod.CASH;
            for (OrderItemEntity item : order.getItems()) {
                if (item.isCanceled()) {
                    continue;
                }
                long amount = (long) item.getUnitPrice() * item.getQty();
                if (!paid) { // REFUND_NEEDED — 상세에는 안 나가고 환불대기 금액으로만 잡힌다
                    if (cash) {
                        totals.cashRefundPending += amount;
                    } else {
                        totals.bankRefundPending += amount;
                    }
                    continue;
                }
                details.add(new DetailRow(
                        order.getOrderNo(), emptyIfNull(order.getTableLabel()), formatTime(order.getCreatedAt()),
                        item.getMenuName(), item.getQty(), item.getUnitPrice(), amount,
                        order.getPaymentStatus().name(),
                        order.getPaymentMethod() == null ? "" : order.getPaymentMethod().name()));
                if (cash) {
                    totals.cashPaid += amount;
                } else {
                    totals.bankPaid += amount;
                }

                MenuSales sales = salesByMenu.computeIfAbsent(item.getMenuName(), MenuSales::new);
                sales.qty += item.getQty();
                sales.amount += amount;
            }
        }

        // 워크북 조립은 트랜잭션 밖에서 — DB에서 읽을 건 다 읽었고, POI 직렬화(최대 31일치)가 CPU를 쓰는 동안
        // 커넥션을 붙들고 있으면 기본 풀 10개에서 주문·결제 API가 그 뒤에 줄을 선다
        return buildResult(booth.getId(), startAt, endAt, details, salesByMenu, totals);
    }

    private Result buildResult(Long boothId, LocalDateTime startAt, LocalDateTime endAt, List<DetailRow> details,
            Map<String, MenuSales> salesByMenu, Totals totals) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            // 요약을 앞 시트에 둬서 파일을 열면 총매출과 메뉴별 집계가 먼저 보이게 한다
            writeSummarySheet(workbook, styles, startAt, endAt, salesByMenu, totals);
            writeDetailSheet(workbook, styles, details);
            workbook.write(out);
            return new Result(boothId, startAt, endAt, out.toByteArray());
        } catch (IOException e) {
            // 메모리 위에서만 쓰기 때문에 실제로 날 수 없다 — 체크 예외를 삼키지 않고 드러내기만 한다
            throw new UncheckedIOException(e);
        }
    }

    /** 수단별 집계 — 계좌이체와 현금은 대사 상대가 달라서(은행 거래내역 vs 현금통) 끝까지 나눠 센다 */
    private static final class Totals {
        private long bankPaid;
        private long cashPaid;
        private long bankRefundPending;
        private long cashRefundPending;

        private long totalPaid() {
            return bankPaid + cashPaid;
        }

        private long refundPending() {
            return bankRefundPending + cashRefundPending;
        }
    }

    private void writeSummarySheet(Workbook workbook, Styles styles, LocalDateTime startAt, LocalDateTime endAt,
            Map<String, MenuSales> salesByMenu, Totals totals) {
        Sheet sheet = workbook.createSheet(SUMMARY_SHEET);
        for (int i = 0; i < SUMMARY_WIDTHS.length; i++) {
            sheet.setColumnWidth(i, SUMMARY_WIDTHS[i] * 256);
        }

        int r = 0;
        Row period = sheet.createRow(r++);
        text(period, 0, "정산 기간", styles.label);
        text(period, 1, formatTime(startAt) + " ~ " + formatTime(endAt), styles.plain);
        r++; // 빈 줄

        r = money(sheet, r, "총 결제완료 매출액", totals.totalPaid(), styles.label, styles.totalMoney);
        r = money(sheet, r, "  계좌이체", totals.bankPaid, styles.plain, styles.money);
        r = money(sheet, r, "  현금", totals.cashPaid, styles.plain, styles.money);
        r++; // 빈 줄

        r = money(sheet, r, "환불대기 금액", totals.refundPending(), styles.label, styles.money);
        r = money(sheet, r, "  계좌이체", totals.bankRefundPending, styles.plain, styles.money);
        r = money(sheet, r, "  현금", totals.cashRefundPending, styles.plain, styles.money);
        r++; // 빈 줄

        // 대사용 — 계좌이체분은 은행 거래내역과, 현금분은 현금통과 맞춘다. 둘을 합치면 어느 쪽과도 안 맞는다.
        r = money(sheet, r, "계좌 입금 합계", totals.bankPaid + totals.bankRefundPending,
                styles.label, styles.money);
        r = money(sheet, r, "현금 보유 합계", totals.cashPaid + totals.cashRefundPending,
                styles.label, styles.money);
        r++; // 빈 줄

        Row menuHeader = sheet.createRow(r++);
        for (int i = 0; i < MENU_SUMMARY_HEADERS.length; i++) {
            text(menuHeader, i, MENU_SUMMARY_HEADERS[i], styles.header);
        }

        List<MenuSales> rows = new ArrayList<>(salesByMenu.values());
        // 많이 판 메뉴가 위로 오게 매출액 내림차순, 같으면 메뉴명 오름차순(출력 순서를 결정적으로)
        rows.sort(Comparator.comparingLong((MenuSales s) -> s.amount).reversed()
                .thenComparing(s -> s.menuName));
        long totalQty = 0L;
        for (MenuSales sales : rows) {
            Row row = sheet.createRow(r++);
            text(row, 0, sales.menuName, styles.plain);
            number(row, 1, sales.qty, styles.count);
            number(row, 2, sales.amount, styles.money);
            totalQty += sales.qty;
        }
        Row totalRow = sheet.createRow(r);
        text(totalRow, 0, "합계", styles.header);
        number(totalRow, 1, totalQty, styles.headerNumber);
        number(totalRow, 2, totals.totalPaid(), styles.headerNumber);
    }

    /** 요약의 "라벨 | 금액" 한 줄. 다음 행 번호를 돌려준다. */
    private int money(Sheet sheet, int rowNum, String label, long value,
            CellStyle labelStyle, CellStyle valueStyle) {
        Row row = sheet.createRow(rowNum);
        text(row, 0, label, labelStyle);
        number(row, 1, value, valueStyle);
        return rowNum + 1;
    }

    private void writeDetailSheet(Workbook workbook, Styles styles, List<DetailRow> details) {
        Sheet sheet = workbook.createSheet(DETAIL_SHEET);
        for (int i = 0; i < DETAIL_WIDTHS.length; i++) {
            sheet.setColumnWidth(i, DETAIL_WIDTHS[i] * 256);
        }

        Row header = sheet.createRow(0);
        for (int i = 0; i < DETAIL_HEADERS.length; i++) {
            text(header, i, DETAIL_HEADERS[i], styles.header);
        }
        sheet.createFreezePane(0, 1); // 스크롤해도 머리글이 남아 있게
        // 결과가 비면 머리글 행만 있다 — 없는 행까지 범위에 넣지 않는다
        sheet.setAutoFilter(new CellRangeAddress(0, details.size(), 0, DETAIL_HEADERS.length - 1));

        int r = 1;
        for (DetailRow detail : details) {
            Row row = sheet.createRow(r++);
            text(row, 0, detail.orderNo(), styles.plain);
            text(row, 1, detail.tableLabel(), styles.plain);
            text(row, 2, detail.createdAt(), styles.plain);
            text(row, 3, detail.menuName(), styles.plain);
            number(row, 4, detail.qty(), styles.count);
            number(row, 5, detail.unitPrice(), styles.money);
            number(row, 6, detail.amount(), styles.money);
            text(row, 7, detail.paymentStatus(), styles.plain);
            text(row, 8, detail.paymentMethod(), styles.plain);
        }
    }

    private static void text(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void number(Row row, int column, long value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** 워크북 하나에서 재사용할 셀 서식 — POI는 스타일 개수에 상한이 있어 셀마다 새로 만들면 안 된다 */
    private static final class Styles {
        private final CellStyle plain;
        private final CellStyle header;
        private final CellStyle headerNumber;
        private final CellStyle label;
        private final CellStyle money;
        private final CellStyle totalMoney;
        private final CellStyle count;

        private Styles(Workbook workbook) {
            Font bold = workbook.createFont();
            bold.setBold(true);

            plain = workbook.createCellStyle();

            header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);

            headerNumber = workbook.createCellStyle();
            headerNumber.cloneStyleFrom(header);
            headerNumber.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));

            label = workbook.createCellStyle();
            label.setFont(bold);

            money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));

            Font bigBold = workbook.createFont();
            bigBold.setBold(true);
            bigBold.setFontHeightInPoints((short) 14);
            totalMoney = workbook.createCellStyle();
            totalMoney.setFont(bigBold);
            totalMoney.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));

            count = workbook.createCellStyle();
            count.setAlignment(HorizontalAlignment.RIGHT);
        }
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(TIMESTAMP_FORMAT);
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
