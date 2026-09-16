package com.boothlock.boothlock_server.tableqr.controller;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.repository.StaffAccountRepository;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableStatus;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionCreateRequest;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionResponse;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.service.TableAdminService;
import com.boothlock.boothlock_server.tableqr.service.TableSessionService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 삭제(soft delete)된 테이블은 어느 경로로도 영업할 수 없다 (audit2 H2·H5·M5).
 * 이력이 있는 테이블을 지우면 행은 남고 active=false가 되는데, main에서는 그 QR로 스캔·주문·O5·O22·O6·O14가 전부 통과했고
 * O3 목록에서만 사라져 운영자가 볼 수 없는 테이블로 주문이 들어왔다. 여기서는 C1·O4·O4b·O5·O6·O22·O14·E1을 한 번씩 확인하고,
 * 삭제 vs 스캔 동시성(100판)과 O5 재발급 vs 첫 스캔(50판)을 실제 스레드로 겹친다.
 */
@SpringBootTest(properties = "boothlock.event.booths-cache-seconds=0")
@AutoConfigureMockMvc
class TableSoftDeleteGuardTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Pattern PDF_PAGE_COUNT = Pattern.compile("/Count\\s+(\\d+)");

    @Autowired MockMvc mockMvc;
    @Autowired TableAdminService tableAdminService;
    @Autowired TableSessionService tableSessionService;
    @Autowired BoothJwtProvider jwtProvider;
    @Autowired BoothRepository boothRepository;
    @Autowired StaffAccountRepository staffRepository;
    @Autowired TableRepository tableRepository;
    @Autowired TableSessionRepository tableSessionRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired DailyCounterRepository dailyCounterRepository;
    @Autowired MenuRepository menuRepository;

    private BoothEntity booth;
    private String authorization;
    private Long menuId;

    @BeforeEach
    void setUp() {
        cleanUp();
        booth = boothRepository.save(new BoothEntity("삭제 가드 부스", "은행 1234", null));
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("password");
        StaffAccountEntity staff = staffRepository.save(new StaffAccountEntity(booth, "admin", hash,
                LocalDateTime.of(2026, 8, 13, 12, 0), StaffRole.ADMIN));
        authorization = "Bearer " + jwtProvider.issue(staff, Instant.now());
        menuId = menuRepository.save(new MenuEntity(booth, "김치전", 8000, null, null, true)).getId();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        orderRepository.deleteAll();
        dailyCounterRepository.deleteAll();
        menuRepository.deleteAll();
        tableSessionRepository.deleteAll();
        tableRepository.deleteAll();
        staffRepository.deleteAll();
        boothRepository.deleteAll();
    }

    /** 이용 이력(종료된 세션)이 있는 테이블 — 삭제하면 soft delete 경로를 탄다 */
    private TableEntity tableWithHistory(String label) {
        TableEntity table = tableRepository.save(new TableEntity(booth, label, "tok-" + label));
        TableSessionEntity past = tableSessionRepository.save(new TableSessionEntity(table, "sess-past-" + label, LocalDateTime.now(KST).minusHours(2)));
        past.end(LocalDateTime.now(KST).minusHours(1));
        tableSessionRepository.save(past);
        return table;
    }

    private void softDelete(TableEntity table) throws Exception {
        mockMvc.perform(delete("/api/v1/admin/tables/{tableId}", table.getId()).header("Authorization", authorization))
                .andExpect(status().isNoContent());
        TableEntity reloaded = tableRepository.findById(table.getId()).orElseThrow();
        assertFalse(reloaded.isActive(), "이력이 있는 테이블은 soft delete여야 한다");
    }

    // ── 삭제된 테이블은 전 경로에서 404·제외 ─────────────

    @Test
    void deletedTableIsRejectedOrHiddenOnEveryPath() throws Exception {
        TableEntity kept = tableRepository.save(new TableEntity(booth, "A-1", "tok-A-1"));
        TableEntity deleted = tableWithHistory("A-2");
        softDelete(deleted);

        // C1 스캔 — 인쇄된 옛 QR은 404
        mockMvc.perform(post("/api/v1/table-sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"tok-A-2\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        // O5 재발급
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/regenerate-token", deleted.getId()).header("Authorization", authorization))
                .andExpect(status().isNotFound());
        // O22 좌표
        mockMvc.perform(patch("/api/v1/admin/tables/{tableId}/position", deleted.getId()).header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"posX\":1,\"posY\":2}"))
                .andExpect(status().isNotFound());
        // O6 퇴실
        mockMvc.perform(post("/api/v1/admin/tables/{tableId}/checkout", deleted.getId()).header("Authorization", authorization))
                .andExpect(status().isNotFound());
        // O4 QR 단건
        mockMvc.perform(get("/api/v1/admin/tables/{tableId}/qr", deleted.getId()).header("Authorization", authorization))
                .andExpect(status().isNotFound());
        // 재삭제
        mockMvc.perform(delete("/api/v1/admin/tables/{tableId}", deleted.getId()).header("Authorization", authorization))
                .andExpect(status().isNotFound());
        // O14 수기 주문 tableId — 세션 생성 경계(TableSessionWriter)가 404로 막아 테이블이 점유되지 않는다
        mockMvc.perform(post("/api/v1/admin/orders").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":" + deleted.getId() + ",\"items\":[{\"menuId\":" + menuId + ",\"qty\":1}]}"))
                .andExpect(status().isNotFound());
        assertEquals(TableStatus.EMPTY, tableRepository.findById(deleted.getId()).orElseThrow().getStatus());
        assertTrue(tableSessionRepository.findOpenByTableIds(List.of(deleted.getId())).isEmpty());
        assertEquals(0, orderRepository.count());

        // O3·E1·O4b — 남은 테이블만 센다
        mockMvc.perform(get("/api/v1/admin/tables").header("Authorization", authorization))
                .andExpect(jsonPath("$.tables.length()").value(1))
                .andExpect(jsonPath("$.tables[0].id").value(kept.getId()));
        mockMvc.perform(get("/api/v1/event/booths"))
                .andExpect(jsonPath("$.booths[0].tables.total").value(1))
                .andExpect(jsonPath("$.booths[0].tables.empty").value(1));
        byte[] pdf = mockMvc.perform(get("/api/v1/admin/tables/qr.pdf").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals(1, pdfPageCount(pdf), "일괄 PDF에 삭제된 테이블 QR이 들어가면 안 된다");
    }

    /** 삭제된 테이블만 남은 부스의 일괄 PDF는 "등록된 테이블이 없음"과 같다 */
    @Test
    void bulkPdfWithOnlyDeletedTablesIsNotFound() throws Exception {
        softDelete(tableWithHistory("A-1"));

        mockMvc.perform(get("/api/v1/admin/tables/qr.pdf").header("Authorization", authorization))
                .andExpect(status().isNotFound());
    }

    /** 삭제 직전까지 열려 있던 세션이 있으면 삭제는 409고, 그 세션 토큰은 계속 유효하다(손님을 쫓아내지 않는다) */
    @Test
    void deleteIsRefusedWhileSessionIsOpen() throws Exception {
        TableEntity table = tableRepository.save(new TableEntity(booth, "A-1", "tok-A-1"));
        String token = tableSessionService.createOrRestore(new TableSessionCreateRequest("tok-A-1")).sessionToken();

        mockMvc.perform(delete("/api/v1/admin/tables/{tableId}", table.getId()).header("Authorization", authorization))
                .andExpect(status().isConflict());

        assertTrue(tableRepository.findById(table.getId()).orElseThrow().isActive());
        mockMvc.perform(get("/api/v1/orders").header("X-Session-Token", token)).andExpect(status().isOk());
    }

    // ── 삭제 vs 스캔 동시성 ─────────────────────────────

    /**
     * 삭제와 첫 스캔을 100판(soft 50·hard 50) 겹친다. 허용되는 결말은 두 가지뿐이다 —
     * "삭제 성공 + 스캔 404" 또는 "스캔 성공 + 삭제 409(사용 중)". 500(외래키)·비활성 테이블의 열린 세션은 0이어야 한다
     */
    @Test
    void deleteRacingFirstScanNeverLeavesSessionOnDeletedTableOrFails() throws Exception {
        int deletedThenScan404 = 0;
        int scannedThenDelete409 = 0;
        for (int round = 0; round < 100; round++) {
            boolean withHistory = round % 2 == 0;
            String label = "R" + round;
            TableEntity table = withHistory ? tableWithHistory(label) : tableRepository.save(new TableEntity(booth, label, "tok-" + label));
            TableSessionCreateRequest scan = new TableSessionCreateRequest("tok-" + label);

            List<Object> results = race(List.of(
                    () -> {
                        tableAdminService.deleteTable(authorization, table.getId());
                        return "deleted";
                    },
                    () -> tableSessionService.createOrRestore(scan)));

            Object deleteResult = results.get(0);
            Object scanResult = results.get(1);
            if ("deleted".equals(deleteResult)) {
                assertTrue(scanResult instanceof NotFoundException, "삭제됐는데 스캔 결과가 404가 아니다: " + scanResult);
                deletedThenScan404++;
                TableEntity reloaded = tableRepository.findById(table.getId()).orElse(null);
                if (withHistory) {
                    assertFalse(reloaded.isActive());
                } else {
                    assertTrue(reloaded == null, "이력 없는 테이블은 행이 지워져야 한다");
                }
                assertTrue(tableSessionRepository.findOpenByTableIds(List.of(table.getId())).isEmpty(), "삭제된 테이블에 열린 세션이 남았다");
            } else {
                assertTrue(deleteResult instanceof InvalidStateException, "삭제 실패 이유가 409(사용 중)가 아니다: " + deleteResult);
                assertTrue(scanResult instanceof TableSessionResponse, "스캔이 성공해야 하는데: " + scanResult);
                scannedThenDelete409++;
                assertTrue(tableRepository.findById(table.getId()).orElseThrow().isActive());
                assertEquals(1, tableSessionRepository.findOpenByTableIds(List.of(table.getId())).size());
                // 다음 판이 "마지막 테이블" 규칙에 걸리지 않게 이 판의 세션을 정리한다
                tableAdminService.checkoutTable(authorization, table.getId());
                tableAdminService.deleteTable(authorization, table.getId());
            }
        }
        System.out.println("[delete vs scan] deleted→scan404=" + deletedThenScan404 + ", scanned→delete409=" + scannedThenDelete409);
    }

    // ── O5 재발급 vs C1 첫 스캔 (M5) ────────────────────

    /** 재발급과 첫 스캔이 겹쳐도 새 토큰이 옛 값으로 되돌아가지 않는다(@DynamicUpdate) — 유출된 옛 QR이 다시 살아나면 안 된다 */
    @Test
    void regenerateRacingFirstScanKeepsNewTokenAndSession() throws Exception {
        for (int round = 0; round < 50; round++) {
            String label = "G" + round;
            String oldToken = "tok-" + label;
            TableEntity table = tableRepository.save(new TableEntity(booth, label, oldToken));
            TableSessionCreateRequest scan = new TableSessionCreateRequest(oldToken);

            List<Object> results = race(List.of(
                    () -> tableAdminService.regenerateToken(authorization, table.getId()),
                    () -> tableSessionService.createOrRestore(scan)));

            assertFalse(results.get(0) instanceof Throwable, "재발급 중 예외: " + results.get(0));
            // 스캔은 재발급 전이면 성공, 후면 404 — 둘 다 허용. 500만 없어야 한다
            assertTrue(results.get(1) instanceof TableSessionResponse || results.get(1) instanceof NotFoundException,
                    "스캔 결과가 예상 밖: " + results.get(1));

            TableEntity reloaded = tableRepository.findById(table.getId()).orElseThrow();
            assertNotEquals(oldToken, reloaded.getTableToken(), "재발급 200인데 DB 토큰이 옛 값으로 되돌아갔다");
            if (results.get(1) instanceof TableSessionResponse) {
                assertEquals(TableStatus.OCCUPIED, reloaded.getStatus(), "스캔 성공인데 사용중 전환이 재발급에 덮였다");
                assertEquals(1, tableSessionRepository.findOpenByTableIds(List.of(table.getId())).size());
            }
        }
    }

    private static int pdfPageCount(byte[] pdf) {
        Matcher matcher = PDF_PAGE_COUNT.matcher(new String(pdf, StandardCharsets.ISO_8859_1));
        int max = 0;
        while (matcher.find()) {
            max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        return max;
    }

    /** 작업들을 준비시킨 뒤 동시에 출발시켜 결과(반환값 또는 예외)를 순서대로 모은다 */
    private static List<Object> race(List<Callable<?>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return task.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            ready.await();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
