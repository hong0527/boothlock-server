package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TableSessionRepository extends JpaRepository<TableSessionEntity, Long> {

    Optional<TableSessionEntity> findBySessionToken(String sessionToken);

    Optional<TableSessionEntity> findByTableIdAndEndedAtIsNull(Long tableId);

    /** O3 좌석 현황 — 부스의 테이블들 중 활성 세션이 있는 테이블만 한 번에 조회한다 */
    List<TableSessionEntity> findByTableIdInAndEndedAtIsNull(List<Long> tableIds);

    /** 세션 인증 계층 — booth까지 join fetch해서 트랜잭션 밖(컨트롤러 조립 시점)에서도 boothId·tableLabel을 바로 읽게 한다 */
    @Query("select s from TableSessionEntity s join fetch s.table t join fetch t.booth where s.sessionToken = :sessionToken")
    Optional<TableSessionEntity> findBySessionTokenWithTableAndBooth(@Param("sessionToken") String sessionToken);

    /** C6 동시 호출 직렬화 — 세션 row에 배타 락을 걸어 같은 세션의 동시 요청을 줄 세운다 (김재원 추가, 전형준 리뷰 필요) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TableSessionEntity s where s.id = :id")
    Optional<TableSessionEntity> findByIdForUpdate(@Param("id") Long id);
}
