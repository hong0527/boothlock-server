package com.boothlock.boothlock_server.tableqr.repository;

/** 테이블별 미결제 주문 건수 한 줄 (인터페이스 프로젝션) — TableUnpaidOrderRepository 참조 */
public interface TableUnpaidCountRow {

    Long getTableId();

    long getUnpaidOrderCount();

    /** 그중 판정 기준 영업일에 접수된 건수 (SeatIdlePolicy 활성 조건 2) */
    long getUnpaidOrderCountToday();
}
