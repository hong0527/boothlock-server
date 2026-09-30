package com.boothlock.boothlock_server.tableqr.dto;

/** O26 자리 합석 요청(명세서 밖 파일럿) — sourceTableId의 주문을 targetTableId 세션으로 옮기고 source 세션을 종료한다. */
public record TableMergeRequest(Long sourceTableId, Long targetTableId) {
}
