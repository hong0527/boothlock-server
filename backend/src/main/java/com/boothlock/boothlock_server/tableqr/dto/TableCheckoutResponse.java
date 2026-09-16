package com.boothlock.boothlock_server.tableqr.dto;

import com.boothlock.boothlock_server.tableqr.domain.TableStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * O6 퇴실·초기화 응답 — 막지 않고 미결제 여부만 경고로 알려준다(명세서 O6 "Should").
 * unpaidWarning(boolean)은 운영자 프론트가 이미 받는 필드라 그대로 두고, 명세 O6의 {id, label, status, warning}을 더했다.
 * warning은 미결제가 있을 때만 싣는다 — 명세 예시가 미결제 없는 경우 세 필드뿐이라 null이면 필드째 뺀다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableCheckoutResponse(boolean unpaidWarning, Long id, String label, TableStatus status, String warning) {
}
