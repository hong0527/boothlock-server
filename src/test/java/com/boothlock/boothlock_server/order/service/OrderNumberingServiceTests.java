package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.order.domain.DailyCounterId;
import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class OrderNumberingServiceTests {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 22);

    @Autowired
    private OrderNumberingService numberingService;

    @Autowired
    private DailyCounterRepository dailyCounterRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        dailyCounterRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        // 동시성 검증은 진짜 커밋이 필요해 @Transactional 롤백을 못 쓴다 — 직접 정리
        dailyCounterRepository.deleteAll();
    }

    @Test
    void computesBusinessDateWithSixHourBoundary() {
        // 05:59는 전날 영업일, 06:00부터 새 영업일 (명세서 §2)
        assertEquals(LocalDate.of(2026, 8, 21), numberingService.businessDateOf(LocalDateTime.of(2026, 8, 22, 5, 59)));
        assertEquals(LocalDate.of(2026, 8, 22), numberingService.businessDateOf(LocalDateTime.of(2026, 8, 22, 6, 0)));
        assertEquals(LocalDate.of(2026, 8, 22), numberingService.businessDateOf(LocalDateTime.of(2026, 8, 23, 1, 30)));
    }

    @Test
    void refusesToRunWithoutTransaction() {
        // MANDATORY — 트랜잭션 없이 부르면 채번 자체를 거부해야 번호 구멍이 안 생긴다
        assertThrows(IllegalTransactionStateException.class, () ->
                numberingService.nextSeq(1L, BUSINESS_DATE));
    }

    @Test
    void issuesSequentialNumbersFromOne() {
        Integer first = tx.execute(status -> numberingService.nextSeq(1L, BUSINESS_DATE));
        Integer second = tx.execute(status -> numberingService.nextSeq(1L, BUSINESS_DATE));

        assertEquals(1, first);
        assertEquals(2, second);
    }

    @Test
    void isolatesCountersByBoothAndDate() {
        Integer boothOne = tx.execute(status -> numberingService.nextSeq(1L, BUSINESS_DATE));
        Integer boothTwo = tx.execute(status -> numberingService.nextSeq(2L, BUSINESS_DATE));
        Integer nextDay = tx.execute(status -> numberingService.nextSeq(1L, BUSINESS_DATE.plusDays(1)));

        // 부스가 다르거나 영업일이 다르면 각자 1부터 — 카운터가 섞이면 안 된다
        assertEquals(1, boothOne);
        assertEquals(1, boothTwo);
        assertEquals(1, nextDay);
    }

    @Test
    void concurrentRequestsGetDistinctGaplessNumbers() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();          // 8개 스레드를 같은 순간에 출발시켜 레이스를 강제로 만든다
                return tx.execute(status -> numberingService.nextSeq(1L, BUSINESS_DATE));
            }));
        }
        ready.await();
        start.countDown();

        Set<Integer> issued = new HashSet<>();
        for (Future<Integer> future : futures) {
            issued.add(future.get());   // Set이라 중복 번호가 나오면 개수가 줄어든다
        }
        pool.shutdown();

        assertEquals(threads, issued.size());                        // 8개 전부 서로 다른 번호
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8), issued);        // 구멍 없이 1~8

        int lastSeq = dailyCounterRepository
                .findById(new DailyCounterId(1L, BUSINESS_DATE))
                .orElseThrow()
                .getLastSeq();
        assertEquals(threads, lastSeq);                              // DB 최종 상태도 8 — 유실된 갱신 없음
    }

    @Test
    void doesNotConsumeNumberWhenTransactionRollsBack() {
        // 주문 저장이 실패해 트랜잭션이 롤백되는 상황을 흉내 — 번호에 구멍이 나면 안 된다
        tx.execute(status -> {
            numberingService.nextSeq(1L, BUSINESS_DATE);
            status.setRollbackOnly();
            return null;
        });

        Integer next = tx.execute(status -> numberingService.nextSeq(1L, BUSINESS_DATE));
        assertEquals(1, next);   // 롤백된 채번은 소모되지 않았다
    }
}
