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

@Entity
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TableStatus status = TableStatus.EMPTY;

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

    /** C1 세션 발급 — 활성 세션이 없어 새로 만들 때 테이블을 사용중으로 전환한다 */
    public void occupy() {
        this.status = TableStatus.OCCUPIED;
    }

    /** O5 QR 재발급 — 기존 토큰을 즉시 폐기한다. 활성 세션은 table_id로만 연결돼 있어 그대로 유지된다 */
    public void regenerateToken(String newTableToken) {
        this.tableToken = newTableToken;
    }
}
