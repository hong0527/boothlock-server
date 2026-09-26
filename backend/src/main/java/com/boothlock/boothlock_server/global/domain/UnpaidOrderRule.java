package com.boothlock.boothlock_server.global.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * "미결제 주문"의 유일한 정의 — <b>서빙 여부와 무관하게 입금이 안 된 주문</b>:
 * {@code status in (RECEIVED, DONE) and paymentStatus = UNPAID} (팀 결정 9/16).
 *
 * <p>결제는 계좌이체뿐이라 운영자가 완료(O12) 처리한 주문도 입금이 안 됐으면 미수금이다. 완료 처리된 미입금 주문이
 * O3 미결제 건수·O6 퇴실 경고·O24 일괄 입금 대상·유휴 예외에서 빠지면 그 돈은 아무 화면에도 잡히지 않는다.
 * 취소된 미입금(CANCELED+UNPAID)은 받을 돈이 없으므로 미결제가 아니다.
 *
 * <p>이 정의를 쓰는 곳은 다음 다섯 곳이고 전부 {@link #JPQL_CONDITION}(주문 별칭 {@code o})을 그대로 이어 붙인다 —
 * 조건을 한 곳만 바꾸면 UnpaidOrderRuleConsistencyTests가 잡는다:
 * <ol>
 *   <li>O3 {@code unpaidOrderCount} — TableUnpaidOrderRepository.countUnpaidOrdersOfOpenSessions</li>
 *   <li>O6 퇴실 {@code warning} — TableUnpaidOrderRepository.findUnpaidOrdersOfSessionsForUpdate</li>
 *   <li>O24 일괄 입금 대상 — TablePaymentOrderRepository.findUnpaidOfActiveTableSessionsForUpdate</li>
 *   <li>SeatIdlePolicy 미결제 예외(C1·O3) — TableUnpaidOrderRepository.existsUnpaidOrderOn</li>
 *   <li>같은 예외의 E1 사본 — BoothSeatRepository.findSeatSummaries</li>
 * </ol>
 *
 * <p><b>이 정의가 아닌 것</b>: C3 미결제 상한(세션당 PENDING_APPROVAL+RECEIVED+UNPAID 8건, 주문 폭주 방지)·C5 손님 취소·결제 모달 항목 수정
 * 가능 판정(OrderEntity.canCancel/canEditItems)은 "접수 중이면서 미입금"이라는 다른 목적의 조건이라 일부러 여기 두지 않는다.
 *
 * <p><b>PENDING_APPROVAL(O28, v0.6.10)은 의도적으로 제외</b>다 — 운영자가 아직 승인하지 않은 주문은 확정된 채무가
 * 아니라서, 여기 포함시키면 O3 미결제 건수·O24 일괄 입금 대상에 아직 받아들이지도 않은 주문이 잡힌다.
 */
public final class UnpaidOrderRule {

    /** 미결제로 세는 주문 상태 — 취소만 빠진다 */
    public static final Set<OrderStatus> STATUSES = Set.copyOf(EnumSet.of(OrderStatus.RECEIVED, OrderStatus.DONE));

    /**
     * JPQL 조건 조각 — 주문 엔티티 별칭이 {@code o}인 쿼리에 {@code and} 뒤에 그대로 이어 붙인다.
     * 컴파일 상수라 {@code @Query} 문자열 안에서 {@code +}로 잇는다. 괄호로 감싸 두어 앞뒤 {@code and}·{@code or}와 섞여도 우선순위가 바뀌지 않는다
     */
    public static final String JPQL_CONDITION = """
            (o.status in (com.boothlock.boothlock_server.global.domain.OrderStatus.RECEIVED,
                          com.boothlock.boothlock_server.global.domain.OrderStatus.DONE)
             and o.paymentStatus = com.boothlock.boothlock_server.global.domain.PaymentStatus.UNPAID)""";

    private UnpaidOrderRule() {
    }

    /** 같은 정의의 자바 판정 — 테스트 기대값과 화면 밖 계산이 JPQL과 어긋나지 않게 한 곳에서 낸다 */
    public static boolean matches(OrderStatus status, PaymentStatus paymentStatus) {
        return STATUSES.contains(status) && paymentStatus == PaymentStatus.UNPAID;
    }
}
