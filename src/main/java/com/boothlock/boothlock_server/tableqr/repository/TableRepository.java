package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TableRepository extends JpaRepository<TableEntity, Long> {

    Optional<TableEntity> findByTableToken(String tableToken);

    boolean existsByBoothIdAndLabel(Long boothId, String label);

    /** C1 세션 발급 — booth는 LAZY라 트랜잭션 밖(응답 조립 시점)에서 접근하면 LazyInitializationException이 나므로 조회 시점에 함께 가져온다 */
    @Query("select t from TableEntity t join fetch t.booth where t.tableToken = :tableToken")
    Optional<TableEntity> findByTableTokenWithBooth(@Param("tableToken") String tableToken);
}
