package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 요청으로 들어온 tableId(O10 쿼리·O14·O24 본문)를 운영자 부스 소속 테이블로 확인한다 (명세서 §7-4 본문 파라미터 IDOR 방지).
 * 미존재·타 부스·삭제(soft delete)된 테이블을 구분하지 않고 전부 404 — 남의 부스 테이블 존재 여부를 흘리지 않고,
 * 운영자 화면(O3)에서 사라진 테이블에 주문·입금이 붙는 일도 막는다 (§1.4)
 */
@Component
public class BoothTableLookup {

    private final TableRepository tableRepository;

    public BoothTableLookup(TableRepository tableRepository) {
        this.tableRepository = tableRepository;
    }

    /** booth는 LAZY라 소속 비교를 트랜잭션 안에서 끝낸다. 돌려준 엔티티는 기본 컬럼(id·label·tableToken)만 읽을 것 */
    @Transactional(readOnly = true)
    public TableEntity requireTableOfBooth(Long tableId, Long boothId) {
        Objects.requireNonNull(boothId, "boothId must not be null");
        return tableRepository.findById(Objects.requireNonNull(tableId, "tableId must not be null"))
                .filter(TableEntity::isActive)
                .filter(table -> boothId.equals(table.getBooth().getId()))
                .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));
    }
}
