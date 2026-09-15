package com.boothlock.boothlock_server.tableqr.dto;

import com.boothlock.boothlock_server.tableqr.domain.TableStatus;

import java.time.OffsetDateTime;

/**
 * O3 좌석 현황 응답의 테이블 1건 (명세서 O3).
 * needsCleanup은 OCCUPIED인데 활성 세션이 없는 경우다. posX/posY·session·unpaidOrderCount는
 * v0.5 신설 필드 — 명세와 기존 구현(needsCleanup만 반환)의 합집합으로 둘 다 유지한다 (O3 각주 참조).
 * O22 응답도 정확히 같은 형태를 쓴다.
 */
public record TableStatusResponse(
        Long id,
        String label,
        TableStatus status,
        boolean needsCleanup,
        Integer posX,
        Integer posY,
        Session session,
        int unpaidOrderCount) {

    public record Session(OffsetDateTime startedAt, OffsetDateTime lastActivityAt) {
    }
}
