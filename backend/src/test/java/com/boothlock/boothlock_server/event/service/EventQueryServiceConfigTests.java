package com.boothlock.boothlock_server.event.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 좌석 유휴 임계 설정값 검증 (명세서 E1).
 * 임계가 0 이하면 idleSince가 현재 시각 이후가 되어 활성 세션이 하나도 안 잡히고
 * 전 부스가 "빈자리 가득"으로 보인다. 화면만 봐서는 오설정인지 그냥 한가한 건지 구별되지 않아
 * 행사 중에 알아채기 가장 어려운 종류의 오류다. 기동 시점에 막는다.
 */
class EventQueryServiceConfigTests {

    @Test
    @DisplayName("유휴 임계가 0이면 기동을 거부한다")
    void rejectsZeroIdleThreshold() {
        assertThatThrownBy(() -> new EventQueryService(null, null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seat-idle-minutes");
    }

    @Test
    @DisplayName("유휴 임계가 음수면 기동을 거부한다")
    void rejectsNegativeIdleThreshold() {
        assertThatThrownBy(() -> new EventQueryService(null, null, -30, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("거부 메시지에 실제 설정값이 담겨 원인을 바로 알 수 있다")
    void messageCarriesTheOffendingValue() {
        assertThatThrownBy(() -> new EventQueryService(null, null, -30, 10))
                .hasMessageContaining("-30");
    }

    @Test
    @DisplayName("캐시 시간이 음수면 기동을 거부한다 — 0은 캐시 끄기로 허용한다")
    void rejectsNegativeCacheSeconds() {
        assertThatThrownBy(() -> new EventQueryService(null, null, 180, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("booths-cache-seconds");
        assertThatCode(() -> new EventQueryService(null, null, 180, 0)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("1분은 허용한다 — 하한만 막고 짧은 값 자체는 운영 판단에 맡긴다")
    void acceptsOneMinute() {
        assertThatCode(() -> new EventQueryService(null, null, 1, 10)).doesNotThrowAnyException();
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
