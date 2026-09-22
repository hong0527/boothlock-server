package com.boothlock.boothlock_server.tableqr.domain;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
// 변경된 컬럼만 UPDATE — 전체 컬럼을 쓰면 C1의 사용중 전환이 동시에 저장된 O5 새 토큰·O22 좌표·삭제(active=false)를 옛 값으로 덮는다
@DynamicUpdate
@Table(
        name = "booth_table",
        uniqueConstraints = @UniqueConstraint(name = "uq_booth_label", columnNames = {"booth_id", "label"})
)
public class TableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booth_id", nullable = false)
    private BoothEntity booth;

    @Column(nullable = false, length = 20)
    private String label;

    @Column(name = "table_token", nullable = false, unique = true, length = 64)
    private String tableToken;

    // JdbcTypeCode(VARCHAR): Hibernate 6 기본은 DB 네이티브 ENUM 타입 — MySQL에서 enum('EMPTY','OCCUPIED')로 만들어지면
    // 값을 하나 늘릴 때마다 ALTER가 필요하다. 정본은 VARCHAR(20) (DB스키마 §1, OrderEntity의 열거형과 같은 방식)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private TableStatus status = TableStatus.EMPTY;

    // 운영자 배치도 위치(O22) — px, 캔버스 좌상단 원점. 배치 전에는 NULL (DB스키마 §1 참조)
    @Column(name = "pos_x")
    private Integer posX;

    @Column(name = "pos_y")
    private Integer posY;

    // 테이블 삭제 — 이용 이력이 있으면 soft delete만 한다(진짜로 지우면 과거 주문·세션 기록의 외래키가
    // 깨진다). 이력이 없는 마지막 번호 테이블은 TableAdminService가 행 자체를 지운다. 어느 쪽이든 번호는 반납되고,
    // 같은 번호를 다시 추가하면 soft delete된 행이 되살아난다(TableAdminService.addSingleTable).
    // columnDefinition으로 DB 기본값을 둔다 — 이 컬럼이 생기기 전 행이나 raw SQL insert(테스트 등)도 active=true로 채워지게
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean active = true;

    protected TableEntity() {
    }

    public TableEntity(BoothEntity booth, String label, String tableToken) {
        this.booth = booth;
        this.label = label;
        this.tableToken = tableToken;
    }

    public Long getId() {
        return id;
    }

    public BoothEntity getBooth() {
        return booth;
    }

    public String getLabel() {
        return label;
    }

    public String getTableToken() {
        return tableToken;
    }

    public TableStatus getStatus() {
        return status;
    }

    public Integer getPosX() {
        return posX;
    }

    public Integer getPosY() {
        return posY;
    }

    /** O22 배치 좌표 저장 */
    public void updatePosition(Integer posX, Integer posY) {
        this.posX = posX;
        this.posY = posY;
    }

    public boolean isActive() {
        return active;
    }

    /** 테이블 삭제 — 이용 이력이 있는 테이블만 이 soft delete 경로를 탄다 */
    public void deactivate() {
        this.active = false;
    }

    /**
     * 삭제했던 번호를 다시 추가 — 토큰은 그대로 둔다(예전에 인쇄한 같은 번호 QR이 다시 동작한다).
     * 배치 좌표는 비워서 "테이블 추가"가 새 테이블처럼 빈 자리에 놓게 한다. 삭제는 열린 세션이 없을 때만 되므로 status는 EMPTY로 되돌린다
     */
    public void reactivate() {
        this.active = true;
        this.status = TableStatus.EMPTY;
        this.posX = null;
        this.posY = null;
    }

    /** C1 세션 발급 — 활성 세션이 없어 새로 만들 때 테이블을 사용중으로 전환한다 */
    public void occupy() {
        this.status = TableStatus.OCCUPIED;
    }

    /** O6 퇴실·초기화 — 세션 종료 후 테이블을 다음 손님 받을 수 있게 되돌린다 */
    public void vacate() {
        this.status = TableStatus.EMPTY;
    }

    /** O5 QR 재발급 — 기존 토큰을 즉시 폐기한다. 활성 세션은 table_id로만 연결돼 있어 그대로 유지된다 */
    public void regenerateToken(String newTableToken) {
        this.tableToken = newTableToken;
    }
}
