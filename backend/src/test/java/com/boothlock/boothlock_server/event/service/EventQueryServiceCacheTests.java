package com.boothlock.boothlock_server.event.service;

import com.boothlock.boothlock_server.event.repository.BoothSeatRepository;
import com.boothlock.boothlock_server.event.repository.BoothSeatRow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E1 10초 캐시 (명세 §E1·§7-19). 시계를 직접 돌려 만료 경계를 확인한다.
 * 캐시가 없으면 인증 없는 반복 호출이 집계 쿼리를 계속 돌려 주문 API와 DB 연결을 다툰다(실측).
 */
class EventQueryServiceCacheTests {

    private static final long SECOND = 1_000_000_000L;

    private final BoothSeatRepository repository = mock(BoothSeatRepository.class);
    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);

    private EventQueryService service(long cacheSeconds) {
        return new EventQueryService(repository, null, 180, cacheSeconds, clock::get);
    }

    /** 프로젝션 인터페이스를 값 객체로 흉내 낸다 — 목 안에서 목을 만들면 스터빙이 꼬인다 */
    private record Row(Long getBoothId, String getName, String getCategory, boolean isOpen,
                       Integer getMapX, Integer getMapY, long getTotalTables, long getEmptyTables)
            implements BoothSeatRow {
    }

    private static BoothSeatRow row(long id, String category, long empty) {
        return new Row(id, "부스" + id, category, true, null, null, 10L, empty);
    }

    @Test
    @DisplayName("캐시 시간 안의 두 번째 요청은 DB에 가지 않는다")
    void reusesResultWithinTtl() {
        when(repository.findSeatSummaries(any(LocalDateTime.class))).thenReturn(List.of(row(1, "FOOD", 3)));
        EventQueryService service = service(10);

        service.getBooths(null);
        clock.addAndGet(9 * SECOND);
        service.getBooths(null);

        verify(repository, times(1)).findSeatSummaries(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("캐시 시간이 정확히 지나면 새로 읽는다 — 좌석 변화가 10초 넘게 묻히지 않는다")
    void reloadsExactlyAtExpiry() {
        when(repository.findSeatSummaries(any(LocalDateTime.class)))
                .thenReturn(List.of(row(1, "FOOD", 3)))
                .thenReturn(List.of(row(1, "FOOD", 0)));
        EventQueryService service = service(10);

        assertThat(service.getBooths(null).booths().get(0).tables().empty()).isEqualTo(3);
        clock.addAndGet(10 * SECOND - 1);
        assertThat(service.getBooths(null).booths().get(0).tables().empty()).isEqualTo(3);
        clock.addAndGet(1);
        assertThat(service.getBooths(null).booths().get(0).tables().empty()).isEqualTo(0);

        verify(repository, times(2)).findSeatSummaries(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("카테고리가 달라도 같은 캐시를 쓴다 — 필터는 캐시된 전체 목록에서 건다")
    void categoryFilterSharesOneCacheEntry() {
        when(repository.findSeatSummaries(any(LocalDateTime.class)))
                .thenReturn(List.of(row(1, "FOOD", 3), row(2, "CAFE", 1)));
        EventQueryService service = service(10);

        assertThat(service.getBooths("FOOD").booths()).extracting(b -> b.boothId()).containsExactly(1L);
        assertThat(service.getBooths("cafe").booths()).extracting(b -> b.boothId()).containsExactly(2L);
        assertThat(service.getBooths(" ").booths()).hasSize(2);
        assertThat(service.getBooths("NOPE").booths()).isEmpty();

        verify(repository, times(1)).findSeatSummaries(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("0초면 캐시를 끄고 매번 새로 읽는다")
    void zeroDisablesCache() {
        when(repository.findSeatSummaries(any(LocalDateTime.class))).thenReturn(List.of(row(1, "FOOD", 3)));
        EventQueryService service = service(0);

        service.getBooths(null);
        service.getBooths(null);

        verify(repository, times(2)).findSeatSummaries(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("만료 순간 동시에 몰린 요청이 집계 쿼리를 한 번만 돌린다")
    void concurrentRequestsAtExpiryLoadOnce() throws Exception {
        CountDownLatch inQuery = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findSeatSummaries(any(LocalDateTime.class))).thenAnswer(invocation -> {
            inQuery.countDown();
            release.await(5, TimeUnit.SECONDS);   // 첫 요청이 쿼리 중인 동안 나머지가 들어오게 붙잡는다
            return List.of(row(1, "FOOD", 3));
        });
        EventQueryService service = service(10);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    assertThat(service.getBooths(null).booths()).hasSize(1);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(inQuery.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);   // 나머지 스레드가 락 앞에 줄 설 시간을 준다
        release.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        verify(repository, times(1)).findSeatSummaries(any(LocalDateTime.class));
    }
}
