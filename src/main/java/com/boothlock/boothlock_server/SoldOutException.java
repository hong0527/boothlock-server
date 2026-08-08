package com.boothlock.boothlock_server;

import java.util.List;

/** 409 SOLD_OUT — 품절 메뉴 포함. details에 품절 메뉴 전체 목록을 실어 던진다 (명세서 C3) */
public class SoldOutException extends RuntimeException {
    private final List<ErrorResponse.ErrorDetail> soldOutMenus;

    public SoldOutException(List<ErrorResponse.ErrorDetail> soldOutMenus) {
        super("품절된 메뉴가 포함되어 있습니다.");
        this.soldOutMenus = soldOutMenus;
    }

    public List<ErrorResponse.ErrorDetail> getSoldOutMenus() {
        return soldOutMenus;
    }
}
