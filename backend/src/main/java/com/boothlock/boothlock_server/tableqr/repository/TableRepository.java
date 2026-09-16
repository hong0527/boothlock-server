package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /*
     * 아래 두 잠금 조회는 부스 행(BoothRepository.findByIdForUpdate)을 잠근 뒤 "지금 이 부스에 어떤 테이블이 있나"를 판정할 때 쓴다
     * (테이블 추가 채번, 삭제의 "마지막 테이블" 판정). 부스 잠금을 기다린 뒤 일반 조회로 읽으면 MySQL REPEATABLE READ에서는
     * 트랜잭션 첫 SELECT(인증) 시점 스냅샷이 나와, 기다리는 사이 커밋된 추가·삭제가 보이지 않는다. 잠금 읽기는 최신 커밋을 본다.
     * 잠금 순서는 항상 부스 행 → 테이블 행이다 — C1·O6은 테이블 행만 잠그고 부스 행은 잠그지 않아 교착이 생기지 않는다
     */

    /** 테이블 추가 채번 — 삭제(soft delete)된 라벨도 포함한다 (findByBoothId와 같은 범위, 유니크 제약이 그렇다) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TableEntity t where t.booth.id = :boothId")
    List<TableEntity> findByBoothIdForUpdate(@Param("boothId") Long boothId);

    /** 테이블 삭제의 "마지막 테이블" 판정 — 활성 테이블만 (findByBoothIdAndActiveTrue와 같은 범위) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TableEntity t where t.booth.id = :boothId and t.active = true")
    List<TableEntity> findByBoothIdAndActiveTrueForUpdate(@Param("boothId") Long boothId);

    /**
     * C1 세션 발급 — booth는 LAZY라 트랜잭션 밖(응답 조립 시점)에서 접근하면 LazyInitializationException이 나므로 조회 시점에 함께 가져온다.
     * 삭제(soft delete)된 테이블의 QR은 조회 단계에서 걸러 404가 되게 한다 — 운영자 화면에서 사라진 테이블로 주문이 들어오면 안 된다
     */
    @Query("select t from TableEntity t join fetch t.booth where t.tableToken = :tableToken and t.active = true")
    Optional<TableEntity> findActiveByTableTokenWithBooth(@Param("tableToken") String tableToken);

    /**
     * O5 QR 재발급·O22 좌표·O4 QR 다운로드 — 부스 조건을 쿼리에 넣어 타 부스 테이블은 조회 단계에서 404가 되게 하고(존재 은닉),
     * 삭제(soft delete)된 테이블도 404다 — 운영자 화면(O3)에 없는 테이블을 다른 경로로 만질 수 없게 한다
     */
    @Query("select t from TableEntity t where t.id = :id and t.booth.id = :boothId and t.active = true")
    Optional<TableEntity> findActiveByIdAndBoothId(@Param("id") Long id, @Param("boothId") Long boothId);

    /**
     * O6 퇴실·테이블 삭제 — 테이블 row에 배타 락을 건다. C1 세션 생성({@link #findActiveByIdForUpdate})도 같은 row를 먼저 잠그므로
     * 퇴실·삭제와 QR 스캔이 줄을 서서, "세션은 살아 있는데 status는 EMPTY"·"삭제된 테이블에 열린 세션" 같은 어긋난 조합이 남지 않는다.
     * 타 부스·삭제된 테이블은 조회 단계에서 404다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TableEntity t where t.id = :id and t.booth.id = :boothId and t.active = true")
    Optional<TableEntity> findActiveByIdAndBoothIdForUpdate(@Param("id") Long id, @Param("boothId") Long boothId);

    /**
     * C1 세션 생성 — 퇴실(O6)·삭제·동시 스캔과 직렬화하려고 테이블 row를 먼저 잠근다 (TableSessionWriter 주석 참조).
     * 잠금을 기다리는 사이 삭제(soft delete)됐으면 비어 있어 404다 — 비활성 테이블에 세션이 열리지 않는다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TableEntity t where t.id = :id and t.active = true")
    Optional<TableEntity> findActiveByIdForUpdate(@Param("id") Long id);
}
