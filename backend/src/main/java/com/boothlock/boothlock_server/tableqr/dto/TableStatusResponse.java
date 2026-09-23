package com.boothlock.boothlock_server.tableqr.dto;

import com.boothlock.boothlock_server.tableqr.domain.TableStatus;

import java.time.OffsetDateTime;

/**
 * O3 좌석 현황 응답의 테이블 1건 (명세서 O3).
 * needsCleanup은 OCCUPIED인데 활성 세션이 없는 경우다. posX/posY·session·unpaidOrderCount는
 * v0.5 신설 필드 — 명세와 기존 구현(needsCleanup만 반환)의 합집합으로 둘 다 유지한다 (O3 각주 참조).
 * O22 응답도 정확히 같은 형태를 쓴다. gridRow/gridCol은 파일럿용 신설 필드(O22b) — posX/posY(px 드래그)와 별개다.
 *
 * @param session          종료되지 않았고 유휴 정책(SeatIdlePolicy)상 활성인 세션. 없거나 유휴면 null — 손님 홈 화면(E1)이 빈자리로 세는 기준과 같다
 * @param unpaidOrderCount 종료되지 않은 세션(유휴 포함)의 미결제(UnpaidOrderRule: RECEIVED·DONE && UNPAID) 주문 수 — 퇴실 전 확인용
 */
public record TableStatusResponse(
        Long id,
        String label,
        TableStatus status,
        boolean needsCleanup,
        Integer posX,
        Integer posY,
        Integer gridRow,
        Integer gridCol,
        Session session,
        int unpaidOrderCount) {

    /**
     * @param id 세션 PK — O10 OrderSummary.sessionId와 맞춰 "지금 앉은 손님의 주문"을 시각 비교 없이 고르게 한다
     *           (테이블-홈 카드가 테이블마다 O10을 따로 부르지 않고 한 번의 조회 결과를 세션 id로 나눈다). 기존 필드 뒤에 붙였다
     */
    public record Session(OffsetDateTime startedAt, OffsetDateTime lastActivityAt, Long id) {
    }
}
