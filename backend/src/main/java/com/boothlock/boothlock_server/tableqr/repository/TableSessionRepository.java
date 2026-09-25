package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TableSessionRepository extends JpaRepository<TableSessionEntity, Long> {

    Optional<TableSessionEntity> findBySessionToken(String sessionToken);

    /**
     * 테이블 삭제 — 그 테이블의 세션 전부(종료된 것 포함)를 FOR UPDATE로 읽는다. 열린 세션이 있으면 사용 중(409), 한 건이라도 있으면
     * 이용 이력이 있어 완전 삭제 대신 soft delete만 한다. 잠금 읽기인 이유: 테이블 행 잠금을 기다린 뒤 일반 조회로 읽으면
     * MySQL REPEATABLE READ에서는 트랜잭션 첫 SELECT(인증) 시점 스냅샷이 나와, 잠금을 기다리는 사이 커밋된 C1 세션을 못 보고
     * 비활성 테이블에 열린 세션이 남는다(MySQL 8.4 실측). 삭제는 드문 운영자 동작이라 세션 수만큼 잠가도 부담이 없다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TableSessionEntity s where s.table.id = :tableId")
    List<TableSessionEntity> findByTableIdForUpdate(@Param("tableId") Long tableId);

    /*
     * "종료되지 않은 세션" 조회·갱신에는 ended_at_key = 0을 반드시 함께 건다.
     * table_session의 인덱스는 uq_session_active(table_id, ended_at_key) 하나뿐이라 ended_at IS NULL만으로는
     * 두 번째 컬럼을 못 써서 그 테이블의 종료된 과거 세션을 전부 읽는다(MySQL 8.4 실측: 세션 5만 건에서 27ms → 1ms).
     * ended_at과 ended_at_key는 항상 함께 바뀐다 — TableSessionEntity#end와 아래 두 종료 UPDATE가 둘을 같은 문장에서 쓰고,
     * 활동 기록(touchIfActive)은 둘 다 건드리지 않는다(@DynamicUpdate). 그래도 ended_at IS NULL을 남겨 두 조건이 어긋난 행은 열린 세션으로 보지 않는다.
     */

    /** C1 세션 발급 — 테이블의 열린 세션(유휴 포함). 유휴 판정은 SeatIdlePolicy가 한다 */
    @Query("select s from TableSessionEntity s where s.table.id = :tableId and s.endedAtKey = 0 and s.endedAt is null")
    Optional<TableSessionEntity> findOpenByTableId(@Param("tableId") Long tableId);

    /**
     * O6 퇴실 — 테이블 행을 잠근 뒤 종료할 열린 세션을 FOR UPDATE로 읽는다. 주문 저장 트랜잭션이 이 행을 조건부 UPDATE로 잠근 채
     * 주문을 넣는 중이면 여기서 그 커밋을 기다린다. 잠금 읽기라 MySQL REPEATABLE READ에서도 인증 시점 스냅샷이 아닌 최신 커밋을 본다 —
     * 테이블 잠금을 기다리는 사이 열린 세션도 빠뜨리지 않는다. 유니크 제약이 깨진 데이터에서 500이 나지 않게 List로 받는다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TableSessionEntity s where s.table.id = :tableId and s.endedAtKey = 0 and s.endedAt is null")
    List<TableSessionEntity> findOpenByTableIdForUpdate(@Param("tableId") Long tableId);

    /**
     * O3 좌석 현황·O22 응답 — 열린 세션을 테이블 수와 무관하게 한 번에 조회한다.
     * 유휴 여부는 거르지 않는다: 유휴 판정은 SeatIdlePolicy가 메모리에서 하고, 미결제 건수는 유휴 세션에도 붙어야 한다
     */
    @Query("select s from TableSessionEntity s where s.table.id in :tableIds and s.endedAtKey = 0 and s.endedAt is null")
    List<TableSessionEntity> findOpenByTableIds(@Param("tableIds") List<Long> tableIds);

    /** 호환용 — 다른 파트(대시보드·홈 화면 테스트)가 쓰는 옛 이름. 조건은 findOpenByTableId와 같다 */
    default Optional<TableSessionEntity> findByTableIdAndEndedAtIsNull(Long tableId) {
        return findOpenByTableId(tableId);
    }

    /** 호환용 — 다른 파트가 쓰는 옛 이름. 조건은 findOpenByTableIds와 같다 */
    default List<TableSessionEntity> findByTableIdInAndEndedAtIsNull(List<Long> tableIds) {
        return findOpenByTableIds(tableIds);
    }

    /** 세션 인증 계층 — booth까지 join fetch해서 트랜잭션 밖(컨트롤러 조립 시점)에서도 boothId·tableLabel을 바로 읽게 한다 */
    @Query("select s from TableSessionEntity s join fetch s.table t join fetch t.booth where s.sessionToken = :sessionToken")
    Optional<TableSessionEntity> findBySessionTokenWithTableAndBooth(@Param("sessionToken") String sessionToken);

    /** C6 동시 호출 직렬화 — 세션 row에 배타 락을 걸어 같은 세션의 동시 요청을 줄 세운다 (김재원 추가, 전형준 리뷰 필요) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TableSessionEntity s where s.id = :id")
    Optional<TableSessionEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * 활동 기록 — 종료되지 않은 세션만 갱신한다(조건부 UPDATE, DB스키마 원칙 9). 0이면 그 사이 퇴실된 세션이다.
     * 조회한 엔티티를 touch하고 저장하는 방식은 쓰지 않는다: 조회와 저장 사이에 퇴실이 커밋되면
     * 옛 사본이 ended_at을 NULL로 되돌려 이미 쫓겨난 손님 토큰이 다시 살아난다
     */
    @Transactional
    @Modifying
    @Query("update TableSessionEntity s set s.lastActivityAt = :at where s.id = :id and s.endedAtKey = 0 and s.endedAt is null")
    int touchIfActive(@Param("id") Long id, @Param("at") LocalDateTime at);

    /**
     * PartySizePage 제출(자릿세 파일럿 전용, 명세서 밖) — 조건부 UPDATE, 세션 인증(TableSessionAuthService.authenticate)
     * 통과 뒤에 부르지만 그 사이 퇴실(O6)이 끼어들 수 있어 touchIfActive와 같은 조건을 건다. 0건이면 410(SessionExpiredException)
     */
    @Transactional
    @Modifying
    @Query("update TableSessionEntity s set s.partySize = :partySize where s.id = :id and s.endedAtKey = 0 and s.endedAt is null")
    int updatePartySizeIfActive(@Param("id") Long id, @Param("partySize") int partySize);

    /**
     * O6 퇴실 — 테이블의 종료되지 않은 세션을 전부 종료한다. ended_at_key에는 스키마 규칙대로 자기 id를 넣는다
     * (TableSessionEntity#end와 같은 규칙, id는 유일해 같은 테이블의 여러 세션을 같은 순간 종료해도 충돌이 없다).
     * 조건부 UPDATE라 두 번 눌러도 두 번째는 0건으로 끝난다
     */
    @Transactional
    @Modifying
    @Query("""
            update TableSessionEntity s
               set s.endedAt = :endedAt,
                   s.endedAtKey = s.id
             where s.table.id = :tableId
               and s.endedAtKey = 0
               and s.endedAt is null
            """)
    int endOpenSessions(@Param("tableId") Long tableId, @Param("endedAt") LocalDateTime endedAt);

    /**
     * C1 유휴 만료 — 유휴 정책상 비활성인 세션 하나를 종료한다(§1.2 자동 만료는 세션만 종료, 테이블 status는 그대로).
     * 조건부 UPDATE라 동시 스캔·퇴실이 먼저 닫았으면 0건이다
     */
    @Transactional
    @Modifying
    @Query("""
            update TableSessionEntity s
               set s.endedAt = :endedAt,
                   s.endedAtKey = s.id
             where s.id = :id
               and s.endedAtKey = 0
               and s.endedAt is null
            """)
    int endSession(@Param("id") Long id, @Param("endedAt") LocalDateTime endedAt);
}
