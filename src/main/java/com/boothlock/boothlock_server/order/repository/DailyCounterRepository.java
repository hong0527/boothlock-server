package com.boothlock.boothlock_server.order.repository;

import com.boothlock.boothlock_server.order.domain.DailyCounterEntity;
import com.boothlock.boothlock_server.order.domain.DailyCounterId;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface DailyCounterRepository extends JpaRepository<DailyCounterEntity, DailyCounterId> {

    /** 채번용 잠금 조회 — SELECT ... FOR UPDATE. 반드시 트랜잭션 안에서 호출 (명세서 §2) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DailyCounterEntity> findWithLockByBoothIdAndBusinessDate(Long boothId, LocalDate businessDate);

    /**
     * 카운터 행 선점 — 같은 부스 첫 주문 2건이 동시에 와도 복합 PK 충돌 없이 통과 (있으면 무시).
     * MySQL 전용 문법(on duplicate key update) — 레포에서 유일한 네이티브 쿼리이며 H2는 MODE=MySQL로 동작
     */
    @Modifying
    @Query(value = "insert into daily_counter (booth_id, business_date, last_seq) "
            + "values (:boothId, :businessDate, 0) "
            + "on duplicate key update last_seq = last_seq", nativeQuery = true)
    void ensureCounter(@Param("boothId") Long boothId, @Param("businessDate") LocalDate businessDate);
}
