package com.boothlock.boothlock_server.tableqr.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * PartySizePage 제출 — 명세서 밖(자릿세 파일럿 전용). 손님이 고른 인원수를 세션에 저장한다.
 * 범위는 프론트 스테퍼(1~20)와 같다 — {@link com.boothlock.boothlock_server.tableqr.service.TableSessionService}가 검증한다.
 */
public record PartySizeRequest(@Schema(minimum = "1", maximum = "20", example = "2") Integer partySize) {
}
