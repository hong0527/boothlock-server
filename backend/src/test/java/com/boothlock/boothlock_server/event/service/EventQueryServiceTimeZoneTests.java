package com.boothlock.boothlock_server.event.service;

import com.boothlock.boothlock_server.event.domain.EventMapEntity;
import com.boothlock.boothlock_server.event.repository.BoothSeatRepository;
import com.boothlock.boothlock_server.event.repository.EventMapRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.boothlock.boothlock_server.global.seat.SeatIdlePolicy;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * build.gradle이 테스트 JVM을 Asia/Seoul로 고정해서, 서비스가 시스템 기본 시간대로 회귀해도 다른 테스트는 통과한다.
 * 여기서는 JVM 기본 시간대를 UTC로 바꿔 그 회귀를 드러낸다. 배포(java -jar)에는 그 고정이 붙지 않는다.
 */
@ResourceLock("java.util.TimeZone.default")
class EventQueryServiceTimeZoneTests {

    private TimeZone original;

    @BeforeEach
    void forceUtc() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restore() {
        TimeZone.setDefault(original);
    }

    /** 실제 배포와 같은 시스템 시계(UTC 순간)로 만든 정책 */
    private static SeatIdlePolicy policy() {
        return new SeatIdlePolicy(180, Clock.systemUTC(), new OrderNumberingService(null));
    }

    @Test
    void idleSinceIsComputedInKstEvenWhenJvmIsUtc() {
        AtomicReference<LocalDateTime> captured = new AtomicReference<>();
        BoothSeatRepository seats = mock(BoothSeatRepository.class);
        when(seats.findSeatSummaries(any(LocalDateTime.class), any(LocalDate.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return List.of();
        });

        new EventQueryService(seats, null, policy(), 0).getBooths(null);

        LocalDateTime expected = LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusMinutes(180);
        assertThat(Duration.between(captured.get(), expected).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void mapUpdatedAtIsKstOffsetEvenWhenJvmIsUtc() {
        EventMapRepository maps = mock(EventMapRepository.class);
        when(maps.findFirstByOrderByIdDesc()).thenReturn(Optional.of(
                new EventMapEntity("/uploads/event/map.png", 10, 10, LocalDateTime.of(2026, 9, 10, 14, 0))));

        assertThat(new EventQueryService(null, maps, policy(), 0).getMap().updatedAt().toString())
                .isEqualTo("2026-09-10T14:00+09:00");
    }
}
