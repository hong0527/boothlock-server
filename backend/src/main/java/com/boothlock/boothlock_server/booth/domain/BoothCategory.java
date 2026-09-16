package com.boothlock.boothlock_server.booth.domain;

/**
 * 홈 화면 부스 분류 (API 명세 O17·E1, DB스키마 v1.3 booth.category).
 * 컬럼은 VARCHAR라 엔티티는 문자열로 두고, 쓰기 경로(O17·시딩)에서만 이 목록으로 검사한다.
 */
public enum BoothCategory {
    FOOD,
    CAFE,
    GOODS,
    ETC;

    /**
     * 대문자 정확 일치만 허용한다. "food"·" FOOD"를 받아 고쳐 저장하지 않는 이유:
     * E1 필터는 대소문자를 무시하지만 응답의 category는 저장값 그대로 나가므로,
     * 클라이언트가 응답값으로 아이콘·색을 고르면 소문자 저장값에서 화면이 갈린다.
     * 저장값을 하나로 고정하고, 잘못 보내는 클라이언트는 400으로 바로 드러나게 한다.
     */
    public static boolean isValid(String value) {
        if (value == null) return false;
        for (BoothCategory category : values()) {
            if (category.name().equals(value)) return true;
        }
        return false;
    }
}
