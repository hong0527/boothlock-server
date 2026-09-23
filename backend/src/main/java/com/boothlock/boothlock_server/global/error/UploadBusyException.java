package com.boothlock.boothlock_server.global.error;

/**
 * 503 UPLOAD_BUSY — 메뉴 이미지 업로드는 서버 전체에서 한 번에 하나만 디코딩한다(MenuImageUploadService).
 * 앞 업로드가 대기 시간 안에 끝나지 않으면 줄을 무한정 세우지 않고 이 응답으로 돌려보낸다 — 요청 스레드가 쌓이면
 * 같은 서버의 주문·결제 요청까지 밀린다. 클라이언트 잘못이 아니라 서버가 바쁜 것이라 429가 아닌 503이다
 */
public class UploadBusyException extends RuntimeException {
    public UploadBusyException() { super("다른 이미지를 처리하는 중입니다. 잠시 후 다시 올려주세요."); }
}
