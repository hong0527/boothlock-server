package com.boothlock.boothlock_server.booth.repository;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BoothRepository extends JpaRepository<BoothEntity, Long> {

    // 삭제(soft delete)된 테이블은 "등록된 테이블 수"에서 제외한다 (O16)
    @Query(value = "select count(*) from booth_table where booth_id = :boothId and active = true", nativeQuery = true)
    long countTablesByBoothId(@Param("boothId") Long boothId);

    /** 테이블 자동 채번(next_table_seq)용 잠금 조회 — SELECT ... FOR UPDATE. 반드시 트랜잭션 안에서 호출 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BoothEntity b where b.id = :id")
    Optional<BoothEntity> findByIdForUpdate(@Param("id") Long id);
}
