package com.boothlock.boothlock_server.dashboard.dto;

import jakarta.validation.constraints.Size;

/**
 * O13 운영자 취소 요청 (명세서 O13).
 * reason은 선택 — 프론트 디자인에 사유 입력 UI가 없어서, 비어있으면 서비스 계층에서 기본 사유로 채운다.
 */
public record CancelRequest(@Size(max = 100) String reason) {
}
