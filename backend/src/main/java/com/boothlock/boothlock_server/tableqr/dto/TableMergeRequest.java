package com.boothlock.boothlock_server.tableqr.dto;

/**
 * 자리 합석(명세서 밖) 요청 — 이 테이블 손님을 합칠 대상 테이블(손님 있는 테이블) id와, 운영자 화면이 본 두 테이블의 세션 id.
 * 세션 id로 고정하는 이유: 합석은 되돌릴 수 없는데, 화면이 갱신되기 전에 한쪽이 퇴실하고 새 손님이 앉으면 모르는 일행끼리 합쳐진다.
 * 서버의 지금 열린 세션과 다르면 409로 거절한다
 */
public record TableMergeRequest(Long toTableId, Long fromSessionId, Long toSessionId) {
}
