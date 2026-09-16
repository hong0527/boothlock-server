package com.boothlock.boothlock_server.event.service;

import com.boothlock.boothlock_server.event.repository.BoothSeatRepository;
import com.boothlock.boothlock_server.global.seat.SeatIdlePolicy;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 좌석 유휴 임계 설정 (명세서 E1).
 * 임계값 하한 검증은 운영자 좌석 현황(O3)과 공유하는 SeatIdlePolicy로 옮겼다 — SeatIdlePolicyTests 참조.
 * 여기서는 E1이 그 정책의 기준 시각을 그대로 쓰는지와, 설정 손잡이가 배포 파일에 드러나 있는지를 본다.
 */
class EventQueryServiceConfigTests {

    @Test
    @DisplayName("E1 집계는 공유 유휴 정책의 idleSince·영업일을 그대로 쓴다 — O3·C1과 기준이 갈라지지 않는다")
    void usesSharedIdlePolicyThreshold() {
        Instant now = LocalDateTime.of(2026, 9, 15, 18, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        SeatIdlePolicy policy = new SeatIdlePolicy(45, Clock.fixed(now, ZoneOffset.UTC), new OrderNumberingService(null));
        BoothSeatRepository repository = mock(BoothSeatRepository.class);
        when(repository.findSeatSummaries(LocalDateTime.of(2026, 9, 15, 17, 15), LocalDate.of(2026, 9, 15))).thenReturn(List.of());

        new EventQueryService(repository, null, policy, 0).getBooths(null);

        verify(repository).findSeatSummaries(LocalDateTime.of(2026, 9, 15, 17, 15), LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("캐시 시간이 음수면 기동을 거부한다 — 0은 캐시 끄기로 허용한다")
    void rejectsNegativeCacheSeconds() {
        SeatIdlePolicy policy = new SeatIdlePolicy(180, Clock.systemUTC(), new OrderNumberingService(null));
        assertThatThrownBy(() -> new EventQueryService(null, null, policy, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("booths-cache-seconds");
        assertThatCode(() -> new EventQueryService(null, null, policy, 0)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("설정 파일에 손잡이가 드러나 있다 — 코드 기본값으로만 두면 운영자가 존재를 모른다")
    void configKeysAreDeclaredInPropertiesFile() throws Exception {
        // 클래스패스로 읽으면 src/test/resources의 테스트용 설정이 메인 설정을 가린다.
        // 확인하려는 대상은 배포되는 쪽 파일이므로 소스 경로에서 직접 읽는다
        // Gradle은 backend를 작업 디렉터리로 쓰고, IDE는 저장소 루트로 돌리기도 한다 — 둘 다 찾는다
        Path mainProperties = Path.of("src/main/resources/application.properties");
        if (!Files.exists(mainProperties)) {
            mainProperties = Path.of("backend/src/main/resources/application.properties");
        }
        assertThat(mainProperties).as("메인 설정 파일 위치").exists();

        String properties = Files.readString(mainProperties, StandardCharsets.UTF_8);
        // 키가 있는지만 본다. 값까지 박으면 행사 전에 기본값을 조정하는 것만으로 빌드가 깨진다
        assertThat(properties).containsPattern("(?m)^boothlock\\.event\\.seat-idle-minutes=\\d+$");
        assertThat(properties).containsPattern("(?m)^boothlock\\.event\\.booths-cache-seconds=\\d+$");
        assertThat(properties).containsPattern("(?m)^boothlock\\.upload\\.event-dir=\\S+$");
    }
}
