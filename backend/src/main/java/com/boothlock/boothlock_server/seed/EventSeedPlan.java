package com.boothlock.boothlock_server.seed;

import java.util.List;

/**
 * 검증을 모두 통과한 시딩 입력 — 이 객체가 만들어졌다면 DB에 넣을 값에 형식 오류는 없다.
 * 비밀번호는 이미 해석된 평문이므로 toString·로그에 절대 내보내지 않는다.
 */
record EventSeedPlan(List<BoothSeed> booths, MapSeed eventMap) {

    record BoothSeed(String name, String bankAccount, String category, int mapX, int mapY,
                     String operatingHours, AdminSeed admin) {
    }

    record AdminSeed(String loginId, String password) {
        @Override
        public String toString() {
            return "AdminSeed[loginId=" + loginId + ", password=***]";
        }
    }

    record MapSeed(String imageUrl, int width, int height) {
    }
}
