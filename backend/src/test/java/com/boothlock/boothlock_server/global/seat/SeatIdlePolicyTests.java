package com.boothlock.boothlock_server.global.seat;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.order.service.OrderNumberingService;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 좌석 유휴 정책 (명세서 E1·O3, DB스키마 원칙 14).
 * 임계가 0 이하면 idleSince가 현재 시각 이후가 되어 활성 세션이 하나도 안 잡히고
 * 전 부스가 "빈자리 가득"으로 보인다. 화면만 봐서는 오설정인지 그냥 한가한 건지 구별되지 않아
 * 행사 중에 알아채기 가장 어려운 종류의 오류다. 기동 시점에 막는다.
 */
class SeatIdlePolicyTests {

    /** 2026-09-15 18:00:00 KST — 시계는 일부러 UTC로 준다. 정책이 시계의 시간대가 아니라 KST로 옮겨 써야 한다 */
    private static final Instant NOW = LocalDateTime.of(2026, 9, 15, 18, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
    private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 9, 15, 18, 0);

    /** 영업일 계산은 주문 채번과 같은 식을 재사용한다 — 저장소를 쓰지 않는 메서드라 null로 만든다 */
    private static final OrderNumberingService CALENDAR = new OrderNumberingService(null);

    private static SeatIdlePolicy policy(long minutes) {
        return policyAt(NOW, minutes);
    }

    private static SeatIdlePolicy policyAt(Instant instant, long minutes) {
        return new SeatIdlePolicy(minutes, Clock.fixed(instant, ZoneOffset.UTC), CALENDAR);
    }

    private static Instant kst(int year, int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, day, hour, minute, second).atZone(ZoneId.of("Asia/Seoul")).toInstant();
    }

    // ── 하한 (EventQueryServiceConfigTests에서 이동) ──────────────

    @Test
    @DisplayName("유휴 임계가 0이면 기동을 거부한다")
    void rejectsZeroIdleThreshold() {
        assertThatThrownBy(() -> new SeatIdlePolicy(0, CALENDAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seat-idle-minutes");
    }

    @Test
    @DisplayName("유휴 임계가 음수면 기동을 거부한다")
    void rejectsNegativeIdleThreshold() {
        assertThatThrownBy(() -> new SeatIdlePolicy(-30, CALENDAR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("거부 메시지에 실제 설정값이 담겨 원인을 바로 알 수 있다")
    void messageCarriesTheOffendingValue() {
        assertThatThrownBy(() -> new SeatIdlePolicy(-30, CALENDAR))
                .hasMessageContaining("-30");
    }

    @Test
    @DisplayName("1분은 허용한다 — 하한만 막고 짧은 값 자체는 운영 판단에 맡긴다")
    void acceptsOneMinute() {
        assertThatCode(() -> new SeatIdlePolicy(1, CALENDAR)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("설정값 0은 스프링 기동 자체를 실패시킨다 — 설정 키를 실제로 읽는다")
    void springStartupFailsOnZeroProperty() {
        new ApplicationContextRunner()
                .withBean(OrderNumberingService.class, () -> CALENDAR)
                .withBean(SeatIdlePolicy.class)
                .withPropertyValues("boothlock.event.seat-idle-minutes=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("설정이 없으면 180분이다")
    void defaultsTo180Minutes() {
        new ApplicationContextRunner()
                .withBean(OrderNumberingService.class, () -> CALENDAR)
                .withBean(SeatIdlePolicy.class)
                .run(context -> {
                    SeatIdlePolicy bean = context.getBean(SeatIdlePolicy.class);
                    // idleSince를 먼저 읽는다 — 뒤에 읽은 now가 같거나 늦으므로 차이는 180분 이상 180분+수 ms다
                    LocalDateTime idleSince = bean.criteria().idleSince();
                    LocalDateTime now = bean.now();
                    assertThat(java.time.Duration.between(idleSince, now).toMinutes()).isEqualTo(180);
                });
    }

    @Test
    @DisplayName("설정값을 바꾸면 임계가 따라 바뀐다")
    void readsConfiguredMinutes() {
        new ApplicationContextRunner()
                .withBean(OrderNumberingService.class, () -> CALENDAR)
                .withBean(SeatIdlePolicy.class)
                .withPropertyValues("boothlock.event.seat-idle-minutes=45")
                .run(context -> {
                    SeatIdlePolicy bean = context.getBean(SeatIdlePolicy.class);
                    LocalDateTime idleSince = bean.criteria().idleSince();
                    LocalDateTime now = bean.now();
                    assertThat(java.time.Duration.between(idleSince, now).toMinutes()).isEqualTo(45);
                });
    }

    // ── 시각 ─────────────────────────────────────────────

    @Test
    @DisplayName("시계가 UTC여도 now는 KST 벽시계다")
    void nowIsKstRegardlessOfClockZone() {
        assertThat(policy(180).now()).isEqualTo(NOW_KST);
    }

    @Test
    @DisplayName("idleSince = KST 현재 - 임계")
    void idleSinceIsNowMinusThreshold() {
        assertThat(policy(180).criteria().idleSince()).isEqualTo(NOW_KST.minusMinutes(180));
    }

    @Test
    @DisplayName("영업일은 06:00 KST 경계 — 05:59:59는 전날, 06:00:00은 그날 (주문 채번과 같은 식)")
    void businessDateTurnsAtSixAm() {
        assertThat(policyAt(kst(2026, 9, 16, 5, 59, 59), 180).criteria().businessDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(policyAt(kst(2026, 9, 16, 6, 0, 0), 180).criteria().businessDate()).isEqualTo(LocalDate.of(2026, 9, 16));
    }

    @Test
    @DisplayName("시계가 UTC여도 영업일은 KST로 계산한다 — UTC 21:30(전날)은 KST 06:30")
    void businessDateUsesKstNotClockZone() {
        Instant utcPreviousDay = LocalDateTime.of(2026, 9, 15, 21, 30).toInstant(ZoneOffset.UTC);
        assertThat(policyAt(utcPreviousDay, 180).criteria().businessDate()).isEqualTo(LocalDate.of(2026, 9, 16));
    }

    // ── 활성 판정 경계 ───────────────────────────────────

    @Test
    @DisplayName("임계 1초 안쪽 활동은 활성")
    void activeOneSecondInsideThreshold() {
        assertThat(policy(180).criteria().isActive(session(NOW_KST.minusMinutes(180).plusSeconds(1)), false)).isTrue();
    }

    @Test
    @DisplayName("임계와 정확히 같은 시각의 활동은 유휴 — E1 쿼리의 lastActivityAt > idleSince와 같은 방향")
    void idleExactlyAtThreshold() {
        assertThat(policy(180).criteria().isActive(session(NOW_KST.minusMinutes(180)), false)).isFalse();
    }

    @Test
    @DisplayName("임계 1초 바깥 활동은 유휴")
    void idleOneSecondOutsideThreshold() {
        assertThat(policy(180).criteria().isActive(session(NOW_KST.minusMinutes(180).minusSeconds(1)), false)).isFalse();
    }

    @Test
    @DisplayName("유휴여도 현재 영업일 미결제가 있으면 활성 — §7-9 미결제 세션은 영업일 종료로만 만료")
    void idleSessionWithUnpaidOrderTodayStaysActive() {
        assertThat(policy(180).criteria().isActive(session(NOW_KST.minusHours(10)), true)).isTrue();
    }

    @Test
    @DisplayName("방금 활동했어도 종료된 세션은 활성이 아니다 — 미결제가 있어도 마찬가지")
    void endedSessionIsNeverActive() {
        TableSessionEntity ended = session(NOW_KST.minusMinutes(1));
        ReflectionTestUtils.setField(ended, "id", 7L);   // end()는 저장된 세션에만 허용된다
        ended.end(NOW_KST);
        assertThat(policy(180).criteria().isActive(ended, false)).isFalse();
        assertThat(policy(180).criteria().isActive(ended, true)).isFalse();
    }

    @Test
    @DisplayName("ended_at_key만 기록되고 ended_at이 비어 있는 어긋난 행도 활성으로 보지 않는다 — 쿼리의 ended_at_key = 0 조건과 같은 방향")
    void sessionWithNonZeroEndedAtKeyIsNotActive() {
        TableSessionEntity inconsistent = session(NOW_KST.minusMinutes(1));
        ReflectionTestUtils.setField(inconsistent, "endedAtKey", 7L);
        assertThat(policy(180).criteria().isActive(inconsistent, true)).isFalse();
    }

    @Test
    @DisplayName("세션이 없으면 활성이 아니다")
    void nullSessionIsNotActive() {
        assertThat(policy(180).criteria().isActive(null, true)).isFalse();
    }

    private static TableSessionEntity session(LocalDateTime lastActivityAt) {
        TableEntity table = new TableEntity(new BoothEntity("부스", "계좌", null), "A-1", "tok");
        return new TableSessionEntity(table, "sess", lastActivityAt);
    }
}
