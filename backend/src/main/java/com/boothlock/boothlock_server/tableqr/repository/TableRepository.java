package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TableRepository extends JpaRepository<TableEntity, Long> {

    Optional<TableEntity> findByTableToken(String tableToken);

    boolean existsByBoothIdAndLabel(Long boothId, String label);

    /**
     * O2 일괄 등록 — 정규화 라벨의 부스 내 중복 판정에 기존 라벨 전체가 필요하다 (DB스키마 §1 booth_table 주석).
     * soft delete된 라벨도 포함해서 스캔한다 — DB unique 제약이 active 여부와 무관하게 걸려있어, 삭제된 라벨도 재사용하면 충돌한다.
     */
    List<TableEntity> findByBoothId(Long boothId);

    /** O3 좌석 현황 — 삭제(soft delete)된 테이블은 목록에서 제외 */
    List<TableEntity> findByBoothIdAndActiveTrue(Long boothId);

    /** C1 세션 발급 — booth는 LAZY라 트랜잭션 밖(응답 조립 시점)에서 접근하면 LazyInitializationException이 나므로 조회 시점에 함께 가져온다 */
    @Query("select t from TableEntity t join fetch t.booth where t.tableToken = :tableToken")
    Optional<TableEntity> findByTableTokenWithBooth(@Param("tableToken") String tableToken);
}
