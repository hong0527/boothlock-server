package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 자리 합석(명세서 밖) 때 합쳐지는 세션의 직원 호출을 남는 세션으로 옮기는 쓰기 — 대시보드 파트 리포지토리를 고치지 않으려고
 * 이 패키지에 따로 둔다(TableCheckoutOrderRepository와 같은 방식). 호출 카드의 테이블 표시는 세션→테이블로 읽으므로 옮기면 새 자리로 보인다.
 * 호출자는 두 테이블 행과 두 세션 행을 잠근 트랜잭션에서 부른다(TableAdminService.mergeTable)
 */
public interface TableMergeCallRepository extends Repository<StaffCallEntity, Long> {

    @Modifying(flushAutomatically = true)
    @Query("update StaffCallEntity c set c.session = :to where c.session.id = :fromSessionId")
    int moveCallsToSession(@Param("fromSessionId") Long fromSessionId, @Param("to") TableSessionEntity to);
}
