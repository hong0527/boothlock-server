package com.boothlock.boothlock_server.event.repository;

/**
 * E1 집계 결과 한 줄 (인터페이스 프로젝션).
 * Object[] 대신 이름으로 받는다 — 컬럼 순서가 바뀌어도 컴파일러가 잡아준다.
 * 부스 엔티티가 응답 경로에 들어오지 않으므로 계좌 같은 필드가 구조적으로 샐 수 없다 (API 명세 §7-18).
 */
public interface BoothSeatRow {

    Long getBoothId();

    String getName();

    String getCategory();

    boolean isOpen();

    Integer getMapX();

    Integer getMapY();

    long getTotalTables();

    long getEmptyTables();
}
