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

    @Column(name = "depositor_name", length = 50)
    private String depositorName;

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

    // "테이블 추가" 자동 채번용 — 기본적으로 삭제해도 줄어들지 않는다(번호 재사용 금지, DailyCounter와 같은
    // FOR UPDATE 패턴). 단, 마지막 번호를 이용 이력 없이 삭제하면 TableAdminService가 releaseLastTableSeq()로
    // 반납해 번호가 부활하게 한다.
    // columnDefinition으로 DB 기본값을 둔다 — 이 컬럼이 생기기 전 행이나 raw SQL insert(테스트 등)도 채워지게
    @Column(name = "next_table_seq", nullable = false, columnDefinition = "integer default 1")
    private int nextTableSeq = 1;

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

    public String getDepositorName() {
        return depositorName;
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

    /** 채번은 반드시 booth row를 FOR UPDATE로 잠근 트랜잭션 안에서만 호출한다 (DailyCounterEntity.nextSeq와 동일 패턴) */
    public int nextTableSeq() {
        int seq = nextTableSeq;
        nextTableSeq = seq + 1;
        return seq;
    }

    public int getNextTableSeq() {
        return nextTableSeq;
    }

    /** 이용 이력 없는 마지막 번호 테이블을 삭제했을 때만 호출 — 번호를 반납해 다음 채번이 같은 번호를 다시 낸다 */
    public void releaseLastTableSeq() {
        nextTableSeq = nextTableSeq - 1;
    }

    public void updateName(String name) { this.name = name; }
    public void updateBankAccount(String bankAccount) { this.bankAccount = bankAccount; }
    public void updateDepositorName(String depositorName) { this.depositorName = depositorName; }
    public void updateOpen(boolean open) { this.open = open; }
    public void updateOperatingHours(String operatingHours) { this.operatingHours = operatingHours; }
}
