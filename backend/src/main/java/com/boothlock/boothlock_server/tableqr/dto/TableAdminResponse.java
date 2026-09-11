package com.boothlock.boothlock_server.tableqr.dto;

/** O2 일괄 등록·O5 QR 재발급 응답의 테이블 1건 (명세서 O2·O5) */
public record TableAdminResponse(Long id, String label, String qrUrl) {
}
