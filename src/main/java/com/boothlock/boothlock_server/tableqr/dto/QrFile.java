package com.boothlock.boothlock_server.tableqr.dto;

/** O4·O4b QR 다운로드 응답 바이너리 (명세서 O4·O4b) */
public record QrFile(byte[] content, String contentType, String filename) {
}
