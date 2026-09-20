package com.boothlock.boothlock_server.global.seat;

import com.boothlock.boothlock_server.order.service.OrderNumberingService;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 좌석 유휴 정책 — "이 세션의 손님이 아직 자리에 있는가"를 판정하는 유일한 곳 (명세서 §1.2·§7-9, DB스키마 원칙 14).
 *
 * <p><b>활성</b> = 종료되지 않았고(ended_at_key = 0, ended_at IS NULL) 다음 중 하나를 만족하는 세션:
 * <ol>
 *   <li>마지막 활동이 {@link Criteria#idleSince()}보다 <b>엄격히</b> 뒤다 (경계와 같은 시각은 유휴)</li>
 *   <li>현재 영업일({@link Criteria#businessDate()})에 접수된 미결제 주문({@link com.boothlock.boothlock_server.global.domain.UnpaidOrderRule}:
 *       RECEIVED·DONE && UNPAID — 완료 처리된 미입금도 포함)이 있다 —
 *       §7-9 "미결제 주문 보유 세션은 영업일 종료로만 만료". 영업일(06:00 KST 경계)이 바뀌면 전날 미결제는 더 이상 세션을 붙잡지 않는다</li>
 * </ol>
 *
 * <p>이 정의를 쓰는 곳은 세 군데이고 셋이 반드시 같아야 한다 — C1 세션 복원(TableSessionService),
 * O3 session·needsCleanup(TableAdminService), E1 좌석 집계(BoothSeatRepository의 JPQL).
 * 앞의 둘은 {@link Criteria#isActive}를, E1은 같은 {@link Criteria} 값으로 같은 조건을 쿼리에 옮겨 쓴다.
 * 조건을 바꾸면 세 곳을 함께 바꾸고 SeatIdleConsistencyTests(교차 확인)를 돌려라.
 *
 * <p>영업일 계산은 주문 채번의 {@link OrderNumberingService#businessDateOf}를 그대로 쓴다 —
 * 주문의 business_date와 같은 식이어야 "현재 영업일의 미결제"가 어긋나지 않는다.
 */
@Component
public class SeatIdlePolicy {

    /** JVM 기본 시간대에 기대지 않는다 — java -jar 배포에는 -Duser.timezone이 붙지 않는다 */
    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

    private final Duration idleThreshold;
    private final Clock clock;
    private final OrderNumberingService businessCalendar;

    @Autowired
    public SeatIdlePolicy(
            // 이 시간 동안 주문·조회가 없으면 그 테이블을 빈자리로 센다.
            // 명세 §1.2의 세션 유휴 만료(3시간)를 기본값으로 두되, 행사 회전 속도에 맞춰 조정할 수 있게 설정으로 뺀다
            @Value("${boothlock.event.seat-idle-minutes:180}") long idleMinutes,
            OrderNumberingService businessCalendar) {
        this(idleMinutes, Clock.systemUTC(), businessCalendar);
    }

    /**
     * 경계 테스트용 — 시각 주입은 이 생성자로만 한다(전역 Clock 빈을 두지 않는다).
     * 시계의 시간대는 무시하고 순간(instant)만 KST로 옮겨 쓰므로 UTC 시계를 넘겨도 KST 규칙이 깨지지 않는다
     */
    public SeatIdlePolicy(long idleMinutes, Clock clock, OrderNumberingService businessCalendar) {
        // 0 이하면 idleSince가 현재 시각 이후가 되어 활성 세션이 하나도 안 잡힌다 —
        // 전 부스가 "빈자리 가득"으로 보이고, 화면만 봐서는 오설정인지 한가한 건지 구별되지 않는다. 기동 시점에 막는다
        if (idleMinutes <= 0) {
            throw new IllegalArgumentException(
                    "boothlock.event.seat-idle-minutes는 1 이상이어야 합니다. 현재 값: " + idleMinutes);
        }
        this.idleThreshold = Duration.ofMinutes(idleMinutes);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.businessCalendar = Objects.requireNonNull(businessCalendar, "businessCalendar must not be null");
    }

    /**
     * 현재 KST 벽시계 시각 — 테이블 파트의 세션 시각(시작·활동·종료)도 이 값으로 기록해 판정 기준과 같은 시계를 쓴다.
     * 마이크로초로 자른다 — orders·table_session 컬럼이 timestamp(6)라 안 자른 나노초 값을 그대로 넣으면 DB가
     * 반올림할 수 있고, order 파트(OrderCreateService 등)는 이미 잘라서 넣으므로 자르지 않으면 두 값을 비교할 때
     * (세션 시작 시각 반올림) > (직후 주문의 잘린 시각)이 되어 활동 시각이 거꾸로 간 것처럼 보일 수 있다(실측, 발생 확률은
     * 극히 낮지만 §1.2 세션 활동 갱신을 함께 쓰는 곳 전부에 영향을 줄 여지가 있어 여기 한 곳에서 막는다).
     */
    public LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), KST_ZONE).truncatedTo(ChronoUnit.MICROS);
    }

    /** 요청 하나 안에서는 한 번만 구해 모든 테이블에 같은 기준을 쓴다 */
    public Criteria criteria() {
        LocalDateTime now = now();
        return new Criteria(now.minus(idleThreshold), businessCalendar.businessDateOf(now));
    }

    /**
     * 활성 판정 기준 한 벌.
     *
     * @param idleSince    이 시각보다 뒤에 활동이 있어야 활성
     * @param businessDate 이 영업일의 미결제(UnpaidOrderRule) 주문이 있으면 유휴여도 활성
     */
    public record Criteria(LocalDateTime idleSince, LocalDate businessDate) {

        /**
         * @param hasUnpaidOrderToday 이 세션에 {@link #businessDate()} 영업일의 미결제(UnpaidOrderRule) 주문이 있는가
         */
        public boolean isActive(TableSessionEntity session, boolean hasUnpaidOrderToday) {
            if (session == null || session.getEndedAt() != null || session.getEndedAtKey() != 0) {
                return false;
            }
            return session.getLastActivityAt().isAfter(idleSince) || hasUnpaidOrderToday;
        }
    }
}
