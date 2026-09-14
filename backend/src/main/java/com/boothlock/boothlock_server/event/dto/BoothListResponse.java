package com.boothlock.boothlock_server.event.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * E1 부스 목록·좌석 현황 응답 (명세서 E1).
 * 공개 API라 여기 없는 필드는 넣지 않는다 — 계좌·매출·주문·토큰은 절대 포함 금지 (§7-18).
 * 부스 엔티티를 그대로 반환하지 않고 이 DTO로 옮겨 담는 이유가 그것이다.
 *
 * <p>중첩 record에 스키마 이름을 명시한다. springdoc은 단순 클래스명으로 스키마를 만들어서,
 * C1 응답(TableSessionResponse)의 {@code Booth}와 이름이 겹치면 Swagger 문서에서 이쪽 필드가 통째로 덮인다(실측).
 */
@Schema(name = "EventBoothListResponse")
public record BoothListResponse(List<Booth> booths) {

    @Schema(name = "EventBooth")
    public record Booth(
            Long boothId,
            String name,
            String category,
            boolean isOpen,
            Integer mapX,
            Integer mapY,
            Tables tables) {
    }

    /** 서버는 숫자만 준다 — 여유·보통·만석 3단계 변환은 화면이 한다 (임계값을 서버에 박으면 행사 중 조정이 안 된다) */
    @Schema(name = "EventBoothTables")
    public record Tables(long total, long empty) {
    }
}
