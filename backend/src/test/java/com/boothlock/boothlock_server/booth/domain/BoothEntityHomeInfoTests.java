package com.boothlock.boothlock_server.booth.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 홈 화면 필드의 부분 수정 단위 — category와 좌표는 서로를 건드리지 않고, 좌표는 반쪽으로 남지 않는다 */
class BoothEntityHomeInfoTests {

    private static BoothEntity booth() {
        BoothEntity booth = new BoothEntity("부스", "계좌", null);
        booth.updateCategory("FOOD");
        booth.updateMapPosition(3200, 5400);
        return booth;
    }

    @Test
    void categoryAndPositionAreIndependent() {
        BoothEntity booth = booth();
        booth.updateCategory("CAFE");
        assertThat(booth.getMapX()).isEqualTo(3200);
        assertThat(booth.getMapY()).isEqualTo(5400);

        booth.updateMapPosition(0, 10000);
        assertThat(booth.getCategory()).isEqualTo("CAFE");
        assertThat(booth.getMapX()).isZero();
        assertThat(booth.getMapY()).isEqualTo(10000);
    }

    @Test
    void bothNullRemovesPin() {
        BoothEntity booth = booth();
        booth.updateMapPosition(null, null);
        assertThat(booth.getMapX()).isNull();
        assertThat(booth.getMapY()).isNull();
        assertThat(booth.getCategory()).isEqualTo("FOOD");
    }

    @Test
    void halfCoordinateIsRejectedAndNothingChanges() {
        BoothEntity booth = booth();
        assertThatThrownBy(() -> booth.updateMapPosition(100, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> booth.updateMapPosition(null, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThat(booth.getMapX()).isEqualTo(3200);
        assertThat(booth.getMapY()).isEqualTo(5400);
    }
}
