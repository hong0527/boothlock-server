package com.boothlock.boothlock_server.global.error;

/**
 * 409 PARTY_SIZE_REQUIRED(명세서 밖, 자릿세 파일럿) — 인원수를 고르지 않은 세션의 손님 주문. 자릿세(1인당 3,000원)가
 * 인원수로 계산되는데, 인원 선택 없이 주문이 들어가면 그 세션의 자릿세가 조용히 0원이 된다. 프론트는 인원 선택 화면으로 보낸다
 */
public class PartySizeRequiredException extends RuntimeException {
    public PartySizeRequiredException() {
        super("인원수를 먼저 선택해주세요.");
    }
}
