package com.boothlock.boothlock_server.settle.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O19 정산 엑셀 (명세서 §7.3, SettlementReportService 상단 주석 참고).
 * 조회 기준 = 주문 생성 시각(createdAt) 구간 [start, end) — 결제완료(PAID) 매출 상세 + 메뉴별·총계 요약.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementReportApiTests {

    private static final DateTimeFormatter PARAM_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String SUMMARY_SHEET = "요약";
    private static final String DETAIL_SHEET = "매출상세";

    @Autowired MockMvc mockMvc;
    @Autowired OrderRepository orderRepository;
    @Autowired StaffAccountRepository staffAccountRepository;
    @Autowired BoothRepository boothRepository;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long boothId;
    private String adminToken;
    private String staffToken;
    private int orderSeq;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();

        BoothEntity booth = boothRepository.save(new BoothEntity("정산 부스", "은행 1234", null));
        boothId = booth.getId();
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        StaffAccountEntity admin = staffAccountRepository.save(new StaffAccountEntity(
                booth, "settle-admin", hash, LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.ADMIN));
        adminToken = jwtProvider.issue(admin, Instant.now());
        StaffAccountEntity staff = staffAccountRepository.save(new StaffAccountEntity(
                booth, "settle-staff", hash, LocalDateTime.of(2026, 9, 1, 12, 0), StaffRole.STAFF));
        staffToken = jwtProvider.issue(staff, Instant.now());
        orderSeq = 0;
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        staffAccountRepository.deleteAll();
        boothRepository.deleteAll();
    }

    @Test
    void generatesWorkbookWithSummaryAndDetailSheets() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 6, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 6, 5), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 6, 10),
                new OrderItemEntity(1L, "김치전", 8_000, 2));

        byte[] body = mockMvc.perform(request(adminToken, start, end))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX_CONTENT_TYPE))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"settlement_" + boothId + "_20260901T060000_20260901T120000.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook workbook = open(body)) {
            // 요약이 첫 시트 — 파일을 열면 총매출이 먼저 보인다
            assertEquals(SUMMARY_SHEET, workbook.getSheetName(0));
            assertEquals(DETAIL_SHEET, workbook.getSheetName(1));

            List<String> detail = row(workbook.getSheet(DETAIL_SHEET), 0);
            assertEquals(
                    List.of("주문번호", "테이블", "주문시각", "메뉴명", "수량", "단가", "금액", "결제상태", "결제수단"),
                    detail);
            assertEquals(
                    List.of("T1-1", "T-1", "2026-09-01 06:05:00", "김치전", "2", "8000", "16000",
                            "PAID", "BANK_TRANSFER"),
                    row(workbook.getSheet(DETAIL_SHEET), 1));
        }
    }

    /**
     * xlsx로 바꾼 이유 그 자체 — CSV로는 지정할 수 없던 열 너비·틀 고정을 서버가 넣는다.
     * 이게 없으면 주문시각 열이 기본 너비보다 길어 엑셀이 {@code #######}로 표시한다.
     */
    @Test
    void setsColumnWidthsAndFreezesHeaderRow() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 10, 5), new OrderItemEntity(1L, "김치전", 8_000, 1));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            Sheet detail = workbook.getSheet(DETAIL_SHEET);
            // 주문시각(2열)은 "2026-09-01 10:00:00" 19자가 들어가야 한다 — 기본 너비(약 8자)로는 ####가 뜬다
            assertTrue(detail.getColumnWidth(2) >= 19 * 256,
                    "주문시각 열 너비가 좁음: " + detail.getColumnWidth(2));
            assertTrue(detail.getColumnWidth(3) >= 20 * 256, "메뉴명 열 너비가 좁음");
            assertNotNull(detail.getPaneInformation(), "머리글 틀 고정이 없음");

            Sheet summary = workbook.getSheet(SUMMARY_SHEET);
            assertTrue(summary.getColumnWidth(0) >= 20 * 256, "요약 메뉴명 열 너비가 좁음");
        }
    }

    /** 화면(datetime-local)은 초 없이 yyyy-MM-ddTHH:mm으로 보낸다 — 이 형태가 깨지면 기능 전체가 400이 된다 */
    @Test
    void acceptsRangeParamsWithoutSeconds() throws Exception {
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 10, 5), new OrderItemEntity(1L, "김치전", 8_000, 1));

        byte[] body = mockMvc.perform(get("/api/v1/admin/reports/settlement.xlsx")
                        .header("Authorization", "Bearer " + adminToken)
                        .queryParam("startAt", "2026-09-01T00:00")
                        .queryParam("endAt", "2026-09-02T00:00"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook workbook = open(body)) {
            assertTrue(detailText(workbook).contains("김치전"));
        }
    }

    @Test
    void filtersByCreatedAtNotApprovedAt() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 6, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        // 생성(createdAt)은 구간 안, 입금확인(approvedAt)은 구간 밖 — createdAt 기준으로 포함돼야 한다
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 6, 5), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 13, 0), new OrderItemEntity(1L, "생성시각기준", 1_000, 1));
        // 생성은 구간 밖, 입금확인은 구간 안 — createdAt 기준이므로 제외돼야 한다
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 5, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 7, 0), new OrderItemEntity(2L, "승인시각기준제외", 1_000, 1));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            String detail = detailText(workbook);
            assertTrue(detail.contains("생성시각기준"));
            assertFalse(detail.contains("승인시각기준제외"));
        }
    }

    @Test
    void excludesUnpaidOrdersFromDetailRows() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.UNPAID, OrderStatus.RECEIVED,
                null, new OrderItemEntity(1L, "미입금메뉴", 5_000, 1));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            assertFalse(detailText(workbook).contains("미입금메뉴"));
            assertEquals(0L, summaryValue(workbook, "총 결제완료 매출액"));
        }
    }

    /** 결제됐다가 취소된 건(REFUND_NEEDED)·환불까지 끝난 건(REFUNDED) 모두 상세에서 빠진다 */
    @Test
    void excludesRefundOrdersFromDetailRows() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 30, 22, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 1, 3, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 30, 22, 30), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 30, 22, 40), new OrderItemEntity(1L, "정상매출", 1_000, 1));
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 30, 23, 0),
                PaymentStatus.REFUND_NEEDED, OrderStatus.CANCELED,
                LocalDateTime.of(2026, 9, 30, 23, 10), new OrderItemEntity(2L, "환불필요", 1_000, 1));
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 10, 1, 0, 0),
                PaymentStatus.REFUNDED, OrderStatus.CANCELED,
                LocalDateTime.of(2026, 10, 1, 0, 10), new OrderItemEntity(3L, "환불완료", 1_000, 1));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            String detail = detailText(workbook);
            assertTrue(detail.contains("정상매출"));
            assertFalse(detail.contains("환불필요"));
            assertFalse(detail.contains("환불완료"));
        }
    }

    @Test
    void excludesOrdersCreatedOutsideRange() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 6, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 5, 59, 59), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 6, 0), new OrderItemEntity(1L, "구간이전", 1_000, 1));
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 12, 0, 1), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 12, 5), new OrderItemEntity(2L, "구간이후", 1_000, 1));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            String detail = detailText(workbook);
            assertFalse(detail.contains("구간이전"));
            assertFalse(detail.contains("구간이후"));
        }
    }

    @Test
    void includesOrderWhenCreatedAtEqualsStartBoundary() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 6, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        newOrder(boothId, "T-1", start, PaymentStatus.PAID, OrderStatus.DONE,
                start, new OrderItemEntity(1L, "시작경계포함", 1_000, 1));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            assertTrue(detailText(workbook).contains("시작경계포함"));
        }
    }

    @Test
    void excludesOrderWhenCreatedAtEqualsEndBoundary() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 6, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 12, 0, 0);
        newOrder(boothId, "T-1", end, PaymentStatus.PAID, OrderStatus.DONE,
                end, new OrderItemEntity(1L, "마감경계제외", 1_000, 1));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            assertFalse(detailText(workbook).contains("마감경계제외"));
        }
    }

    @Test
    void handlesMidnightCrossingRange() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 30, 22, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 1, 3, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 30, 21, 50), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 30, 21, 55), new OrderItemEntity(1L, "구간이전제외", 1_000, 1));
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 10, 1, 2, 10), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 10, 1, 2, 15), new OrderItemEntity(2L, "자정넘김포함", 1_000, 1));
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 10, 1, 3, 30), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 10, 1, 4, 0), new OrderItemEntity(3L, "구간이후제외", 1_000, 1));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            String detail = detailText(workbook);
            assertTrue(detail.contains("자정넘김포함"));
            assertFalse(detail.contains("구간이전제외"));
            assertFalse(detail.contains("구간이후제외"));
        }
    }

    @Test
    void excludesIndividuallyCanceledItemsFromDetailRows() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        Long orderId = newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID,
                OrderStatus.DONE, LocalDateTime.of(2026, 9, 1, 10, 5),
                new OrderItemEntity(1L, "김치전", 8_000, 1), new OrderItemEntity(2L, "제로콜라", 5_000, 1));
        // 지연 로딩 프록시는 트랜잭션 밖이라 못 읽는다 — 메뉴명으로 직접 조회
        jdbcTemplate.update(
                "update order_item set canceled = true where order_id = ? and menu_name = ?",
                orderId, "제로콜라");

        try (Workbook workbook = fetch(adminToken, start, end)) {
            String detail = detailText(workbook);
            assertTrue(detail.contains("김치전"));
            assertFalse(detail.contains("제로콜라"));
        }
    }

    /**
     * 손님·운영자 자유 입력이 그대로 셀에 들어가도 엑셀이 수식으로 실행하면 안 된다.
     * CSV 시절엔 앞에 {@code '}를 붙여 막았는데, xlsx는 셀 타입이 문자열로 박히므로 원문 그대로 둔다.
     */
    @Test
    void writesFormulaLikeMenuNameAsPlainTextCell() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID,
                OrderStatus.DONE, LocalDateTime.of(2026, 9, 1, 10, 5),
                new OrderItemEntity(1L, "=SUM(A1:A9)", 1_000, 1),
                new OrderItemEntity(2L, "메뉴, 특이", 1_000, 1),
                new OrderItemEntity(3L, "따옴표\"메뉴", 1_000, 1));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            Sheet detail = workbook.getSheet(DETAIL_SHEET);
            List<String> menuNames = new ArrayList<>();
            for (int r = 1; r <= detail.getLastRowNum(); r++) {
                Cell cell = detail.getRow(r).getCell(3);
                assertEquals(CellType.STRING, cell.getCellType(), "메뉴명은 문자열 셀이어야 한다");
                menuNames.add(cell.getStringCellValue());
            }
            // 쉼표·따옴표 이스케이프도, 수식 방지용 ' 접두사도 필요 없다 — 값이 원문 그대로 들어간다
            assertTrue(menuNames.contains("=SUM(A1:A9)"), menuNames.toString());
            assertTrue(menuNames.contains("메뉴, 특이"), menuNames.toString());
            assertTrue(menuNames.contains("따옴표\"메뉴"), menuNames.toString());
        }
    }

    @Test
    void onlyIncludesRequestedBooth() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 10, 5), new OrderItemEntity(1L, "포함됨", 1_000, 1));

        BoothEntity otherBooth = boothRepository.save(new BoothEntity("다른 부스", "은행 5678", null));
        OrderEntity otherOrder = new OrderEntity(
                otherBooth.getId(), null, "T99-1", LocalDate.of(2026, 9, 1), 99,
                "settle-other-99", 0, false, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0));
        otherOrder.addItem(new OrderItemEntity(3L, "다른부스메뉴", 1_000, 1));
        Long otherId = orderRepository.save(otherOrder).getId();
        jdbcTemplate.update("update orders set payment_status = 'PAID' where id = ?", otherId);

        try (Workbook workbook = fetch(adminToken, start, end)) {
            String detail = detailText(workbook);
            assertTrue(detail.contains("포함됨"));
            assertFalse(detail.contains("다른부스메뉴"));
        }
    }

    @Test
    void summarizesTotalPaidRevenueAcrossMultipleOrdersAndItems() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 10, 5),
                new OrderItemEntity(1L, "김치전", 8_000, 2), new OrderItemEntity(2L, "막걸리", 5_000, 1));
        newOrder(boothId, "T-2", LocalDateTime.of(2026, 9, 1, 11, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 11, 5),
                new OrderItemEntity(3L, "떡볶이", 6_000, 3));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            // 16000 + 5000 + 18000 = 39000
            assertEquals(39_000L, summaryValue(workbook, "총 결제완료 매출액"));
        }
    }

    /** 메뉴별 집계 — 같은 메뉴는 주문이 달라도 한 줄로 합쳐지고, 매출액 내림차순으로 나온다 */
    @Test
    void summarizesSalesPerMenuSortedByAmountDesc() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 10, 5),
                new OrderItemEntity(1L, "김치전", 8_000, 2), new OrderItemEntity(2L, "막걸리", 5_000, 1));
        newOrder(boothId, "T-2", LocalDateTime.of(2026, 9, 1, 11, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 11, 5),
                new OrderItemEntity(3L, "김치전", 8_000, 1), new OrderItemEntity(4L, "떡볶이", 6_000, 3));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            List<List<String>> menu = menuRows(workbook);
            assertEquals(List.of("김치전", "3", "24000"), menu.get(0)); // 16000 + 8000
            assertEquals(List.of("떡볶이", "3", "18000"), menu.get(1));
            assertEquals(List.of("막걸리", "1", "5000"), menu.get(2));
            assertEquals(List.of("합계", "7", "47000"), menu.get(3));
        }
    }

    /** 환불대기(REFUND_NEEDED)는 매출이 아니지만 돈은 계좌에 있다 — 따로 적고 계좌 합계까지 내준다 */
    @Test
    void reportsRefundPendingAmountSeparatelyFromRevenue() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 10, 5), new OrderItemEntity(1L, "결제완료", 8_000, 1));
        newOrder(boothId, "T-2", LocalDateTime.of(2026, 9, 1, 11, 0), PaymentStatus.REFUND_NEEDED,
                OrderStatus.CANCELED, LocalDateTime.of(2026, 9, 1, 11, 5),
                new OrderItemEntity(2L, "환불대기", 3_000, 1));
        // 환불 송금까지 끝난 건은 계좌에 없다 — 어느 쪽에도 안 잡혀야 한다
        newOrder(boothId, "T-3", LocalDateTime.of(2026, 9, 1, 12, 0), PaymentStatus.REFUNDED,
                OrderStatus.CANCELED, LocalDateTime.of(2026, 9, 1, 12, 5),
                new OrderItemEntity(3L, "환불끝", 2_000, 1));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            assertEquals(8_000L, summaryValue(workbook, "총 결제완료 매출액"));
            assertEquals(3_000L, summaryValue(workbook, "환불대기 금액"));
            assertEquals(11_000L, summaryValue(workbook, "계좌 입금 합계"));
            assertEquals(0L, summaryValue(workbook, "현금 보유 합계"));
        }
    }

    /**
     * 현금 매출을 계좌 입금액에 섞으면 안 된다 — 대사 상대가 다르다.
     * 섞어서 한 줄로 내면 현금을 받은 부스에서는 그 값이 은행 거래내역과 영영 맞지 않는다.
     */
    @Test
    void separatesCashFromBankTransferInSummary() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 10, 5), "BANK_TRANSFER",
                new OrderItemEntity(1L, "계좌매출", 300_000, 1));
        newOrder(boothId, "T-2", LocalDateTime.of(2026, 9, 1, 11, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 11, 5), "CASH",
                new OrderItemEntity(2L, "현금매출", 200_000, 1));
        newOrder(boothId, "T-3", LocalDateTime.of(2026, 9, 1, 12, 0), PaymentStatus.REFUND_NEEDED,
                OrderStatus.CANCELED, LocalDateTime.of(2026, 9, 1, 12, 5), "CASH",
                new OrderItemEntity(3L, "현금환불대기", 50_000, 1));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            assertEquals(500_000L, summaryValue(workbook, "총 결제완료 매출액"));
            assertEquals(300_000L, summaryValue(workbook, "  계좌이체"));
            assertEquals(50_000L, summaryValue(workbook, "환불대기 금액"));
            // 계좌에 있는 돈은 계좌이체 매출뿐 — 현금 250,000원이 섞이면 은행 거래내역과 어긋난다
            assertEquals(300_000L, summaryValue(workbook, "계좌 입금 합계"));
            assertEquals(250_000L, summaryValue(workbook, "현금 보유 합계"));
        }
    }

    /** 상세의 결제수단 컬럼 — 계좌 대사에 쓰는 유일한 단서다 */
    @Test
    void writesPaymentMethodInDetailRows() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID, OrderStatus.DONE,
                LocalDateTime.of(2026, 9, 1, 10, 5), "CASH", new OrderItemEntity(1L, "현금주문", 5_000, 1));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            assertEquals("CASH", workbook.getSheet(DETAIL_SHEET).getRow(1).getCell(8).getStringCellValue());
        }
    }

    @Test
    void excludesIndividuallyCanceledItemAmountFromTotal() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        Long orderId = newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID,
                OrderStatus.DONE, LocalDateTime.of(2026, 9, 1, 10, 5),
                new OrderItemEntity(1L, "김치전", 8_000, 1), new OrderItemEntity(2L, "제로콜라", 5_000, 1));
        jdbcTemplate.update(
                "update order_item set canceled = true where order_id = ? and menu_name = ?",
                orderId, "제로콜라");

        try (Workbook workbook = fetch(adminToken, start, end)) {
            // 취소된 제로콜라(5000)는 총액에서 제외 — 김치전(8000)만 반영
            assertEquals(8_000L, summaryValue(workbook, "총 결제완료 매출액"));
        }
    }

    @Test
    void totalIsZeroWhenPaidOrderHasAllItemsIndividuallyCanceled() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        Long orderId = newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID,
                OrderStatus.DONE, LocalDateTime.of(2026, 9, 1, 10, 5),
                new OrderItemEntity(1L, "김치전", 8_000, 1), new OrderItemEntity(2L, "막걸리", 5_000, 1));
        jdbcTemplate.update("update order_item set canceled = true where order_id = ?", orderId);

        try (Workbook workbook = fetch(adminToken, start, end)) {
            // 결제완료 주문이지만 항목이 전부 개별 취소돼 상세 행 자체가 없다 — 총액도 0원
            String detail = detailText(workbook);
            assertFalse(detail.contains("김치전"));
            assertFalse(detail.contains("막걸리"));
            assertEquals(0L, summaryValue(workbook, "총 결제완료 매출액"));
        }
    }

    /** 상세 합계 = 메뉴별 집계 합계 = 총 결제완료 매출액. 셋 중 하나만 어긋나도 잡힌다. */
    @Test
    void detailMenuSummaryAndTotalAllAgree() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        Long paidOrderId = newOrder(boothId, "T-1", LocalDateTime.of(2026, 9, 1, 10, 0), PaymentStatus.PAID,
                OrderStatus.DONE, LocalDateTime.of(2026, 9, 1, 10, 5),
                new OrderItemEntity(1L, "김치전", 8_000, 2), new OrderItemEntity(2L, "막걸리", 5_000, 1),
                new OrderItemEntity(3L, "제로콜라", 3_000, 4));
        jdbcTemplate.update(
                "update order_item set canceled = true where order_id = ? and menu_name = ?",
                paidOrderId, "제로콜라");
        newOrder(boothId, "T-2", LocalDateTime.of(2026, 9, 1, 11, 0), PaymentStatus.UNPAID, OrderStatus.RECEIVED,
                null, new OrderItemEntity(4L, "미입금떡볶이", 6_000, 3));

        try (Workbook workbook = fetch(adminToken, start, end)) {
            Sheet detail = workbook.getSheet(DETAIL_SHEET);
            long detailSum = 0L;
            for (int r = 1; r <= detail.getLastRowNum(); r++) {
                Row row = detail.getRow(r);
                assertEquals("PAID", row.getCell(7).getStringCellValue(), "상세는 결제완료만");
                detailSum += (long) row.getCell(6).getNumericCellValue();
            }

            List<List<String>> menu = menuRows(workbook);
            long menuSum = 0L;
            for (int i = 0; i < menu.size() - 1; i++) { // 마지막은 합계 행
                menuSum += Long.parseLong(menu.get(i).get(2));
            }

            long total = summaryValue(workbook, "총 결제완료 매출액");
            assertEquals(detailSum, menuSum);
            assertEquals(detailSum, total);
            assertEquals(21_000L, total); // 김치전 16000 + 막걸리 5000
        }
    }

    @Test
    void rejectsWhenStartAtNotBeforeEndAt() throws Exception {
        LocalDateTime same = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
        mockMvc.perform(request(adminToken, same, same))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(request(adminToken, same, same.minusMinutes(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    /** 구간 상한 — 없으면 정산 파일 한 번에 그 부스 주문 전체가 메모리로 올라온다 */
    @Test
    void rejectsRangeWiderThanMaxDays() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        mockMvc.perform(request(adminToken, start, start.plusDays(31).plusSeconds(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void acceptsRangeExactlyAtMaxDays() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        mockMvc.perform(request(adminToken, start, start.plusDays(31)))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsMissingRangeParams() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports/settlement.xlsx")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsStaffRole() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        mockMvc.perform(request(staffToken, start, end))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void rejectsMissingAuthorization() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 2, 0, 0, 0);
        mockMvc.perform(get("/api/v1/admin/reports/settlement.xlsx")
                        .queryParam("startAt", start.format(PARAM_FORMAT))
                        .queryParam("endAt", end.format(PARAM_FORMAT)))
                .andExpect(status().isUnauthorized());
    }

    // --- 워크북 읽기 도우미 ---

    private static Workbook open(byte[] body) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(body));
    }

    private Workbook fetch(String token, LocalDateTime start, LocalDateTime end) throws Exception {
        return open(mockMvc.perform(request(token, start, end))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    /** 한 행의 셀을 문자열로 — 숫자 셀은 정수로 찍는다(금액·수량은 모두 정수) */
    private static List<String> row(Sheet sheet, int rowNum) {
        List<String> cells = new ArrayList<>();
        Row row = sheet.getRow(rowNum);
        if (row == null) {
            return cells;
        }
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell == null) {
                cells.add("");
            } else if (cell.getCellType() == CellType.NUMERIC) {
                cells.add(String.valueOf((long) cell.getNumericCellValue()));
            } else {
                cells.add(cell.getStringCellValue());
            }
        }
        return cells;
    }

    /** 상세 시트 전체를 한 문자열로 — contains 단언용 */
    private static String detailText(Workbook workbook) {
        Sheet sheet = workbook.getSheet(DETAIL_SHEET);
        StringBuilder text = new StringBuilder();
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            text.append(String.join(",", row(sheet, r))).append('\n');
        }
        return text.toString();
    }

    /** 요약 시트에서 라벨(0열)로 금액(1열)을 찾는다 */
    private static long summaryValue(Workbook workbook, String label) {
        Sheet sheet = workbook.getSheet(SUMMARY_SHEET);
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Cell first = row.getCell(0);
            if (first != null && first.getCellType() == CellType.STRING
                    && label.equals(first.getStringCellValue())) {
                return (long) row.getCell(1).getNumericCellValue();
            }
        }
        throw new AssertionError("요약에 '" + label + "' 행이 없음");
    }

    /** 요약 시트의 메뉴별 집계 — 헤더 다음 줄부터 합계 행까지 */
    private static List<List<String>> menuRows(Workbook workbook) {
        Sheet sheet = workbook.getSheet(SUMMARY_SHEET);
        List<List<String>> rows = new ArrayList<>();
        boolean started = false;
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            List<String> cells = row(sheet, r);
            if (!started) {
                started = cells.size() >= 3 && "메뉴명".equals(cells.get(0));
                continue;
            }
            rows.add(cells);
        }
        return rows;
    }

    private Long newOrder(Long orderBoothId, String tableLabel, LocalDateTime createdAt,
            PaymentStatus paymentStatus, OrderStatus orderStatus, LocalDateTime approvedAt,
            OrderItemEntity... items) {
        return newOrder(orderBoothId, tableLabel, createdAt, paymentStatus, orderStatus, approvedAt,
                paymentStatus == PaymentStatus.UNPAID ? null : "BANK_TRANSFER", items);
    }

    private Long newOrder(Long orderBoothId, String tableLabel, LocalDateTime createdAt,
            PaymentStatus paymentStatus, OrderStatus orderStatus, LocalDateTime approvedAt,
            String paymentMethod, OrderItemEntity... items) {
        orderSeq++;
        OrderEntity order = new OrderEntity(
                orderBoothId, null, "T" + orderSeq + "-1", createdAt.toLocalDate(), orderSeq,
                "settle-" + orderBoothId + "-" + orderSeq, 0, false, tableLabel, createdAt);
        for (OrderItemEntity item : items) {
            order.addItem(item);
        }
        Long id = orderRepository.save(order).getId();
        jdbcTemplate.update(
                "update orders set payment_status = ?, payment_method = ?, status = ?, "
                        + "approved_by = ?, approved_at = ? where id = ?",
                paymentStatus.name(), paymentMethod,
                orderStatus.name(),
                paymentStatus == PaymentStatus.UNPAID ? null : "settle-admin",
                approvedAt,
                id);
        return id;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            String token, LocalDateTime start, LocalDateTime end) {
        var req = get("/api/v1/admin/reports/settlement.xlsx")
                .queryParam("startAt", start.format(PARAM_FORMAT))
                .queryParam("endAt", end.format(PARAM_FORMAT));
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return req;
    }
}
