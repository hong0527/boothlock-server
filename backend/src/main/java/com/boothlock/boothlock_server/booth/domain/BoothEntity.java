package com.boothlock.boothlock_server.booth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "booth")
public class BoothEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "bank_account", nullable = false, length = 100)
    private String bankAccount;

    @Column(name = "is_open", nullable = false)
    private boolean open = true;

    @Column(name = "operating_hours", length = 50)
    private String operatingHours;

    // 아래 셋은 손님 홈 화면(E1·E2)용 — 표시 전용이라 주문·정산 로직은 읽지 않는다 (DB스키마 v1.3 §3-10)
    @Column(length = 20)
    private String category;

    // 약도 이미지 기준 0~10000 상대 좌표(0.00%~100.00%) — 이미지를 바꿔도 핀이 제자리에 남도록 픽셀이 아닌 비율로 둔다.
    // Integer로 둔다. 컬럼이 생기기 전부터 있던 부스 행은 좌표가 NULL인데, 프리미티브 int면 그 행을 읽는 순간
    // NULL을 int에 넣지 못해 예외가 난다(H2 파일 DB로 실측 — ddl-auto의 컬럼 추가 자체는 성공한다)
    @Column(name = "map_x")
    private Integer mapX;

    @Column(name = "map_y")
    private Integer mapY;

    protected BoothEntity() {
    }

    public BoothEntity(String name, String bankAccount, String operatingHours) {
        this.name = name;
        this.bankAccount = bankAccount;
        this.operatingHours = operatingHours;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBankAccount() {
        return bankAccount;
    }

    public boolean isOpen() {
        return open;
    }

    public String getCategory() {
        return category;
    }

    public Integer getMapX() {
        return mapX;
    }

    public Integer getMapY() {
        return mapY;
    }

    public void updateHomeInfo(String category, Integer mapX, Integer mapY) {
        this.category = category;
        this.mapX = mapX;
        this.mapY = mapY;
    }

    public String getOperatingHours() {
        return operatingHours;
    }

    public void updateName(String name) { this.name = name; }
    public void updateBankAccount(String bankAccount) { this.bankAccount = bankAccount; }
    public void updateOpen(boolean open) { this.open = open; }
    public void updateOperatingHours(String operatingHours) { this.operatingHours = operatingHours; }
}
