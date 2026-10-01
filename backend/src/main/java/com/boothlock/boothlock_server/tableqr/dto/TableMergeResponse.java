package com.boothlock.boothlock_server.tableqr.dto;

import java.util.List;

/**
 * 자리 합석(명세서 밖) 응답 — 남은 세션(대상 테이블)과 합친 인원수.
 * warnings: 자릿세가 자동으로 맞게 청구되지 않는 경우의 안내(없으면 빈 목록) — 한쪽 일행만 처리된 채 합쳐 나머지 몫이 안 붙거나,
 * 이미 낸 일행이 다음 첫 주문에 또 붙거나, 인원을 몰라 빠지는 경우. 운영자가 결제 모달에서 자릿세 줄을 고치거나 추가 자릿세를 넣는다
 */
public record TableMergeResponse(Long sessionId, Long fromTableId, String fromLabel, Long toTableId, String toLabel,
                                 Integer partySize, List<String> warnings) {
}
