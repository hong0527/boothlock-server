package com.boothlock.boothlock_server.tableqr.repository;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * "테이블 추가" 자동 채번 카운터(booth.next_table_seq) 쓰기 — 부스 파트 리포지토리·엔티티를 고치지 않으려고 이 패키지에 따로 둔다
 * (JPA는 같은 엔티티에 리포지토리를 여러 개 둘 수 있다). 쓰기 메서드가 더 생기지 않도록 빈 Repository를 확장한다.
 *
 * <p>엔티티 필드를 고쳐 더티 체킹에 맡기지 않고 이 컬럼만 UPDATE하는 이유: BoothEntity는 @DynamicUpdate가 없어 필드 하나를 바꿔도
 * 전체 컬럼이 UPDATE된다. 그러면 채번과 동시에 커밋된 O17 부스 설정(계좌·영업 여부·약도 좌표)이 채번 트랜잭션의 옛 사본으로 덮인다.
 * 반대 방향(O17이 카운터를 옛 값으로 되돌리는 것, audit2 H3)은 부스 파트의 @DynamicUpdate가 필요하고, 여기서는 다음 채번이
 * 기존 라벨에서 카운터를 되살려(TableAdminService.addSingleTable) 영구 500으로 굳지 않게만 한다.
 * 호출자는 반드시 부스 행을 FOR UPDATE로 잠근 트랜잭션 안에서 부른다.
 */
public interface TableSequenceRepository extends Repository<BoothEntity, Long> {

    @Modifying
    @Query("update BoothEntity b set b.nextTableSeq = :next where b.id = :boothId")
    int setNextTableSeq(@Param("boothId") Long boothId, @Param("next") int next);
}
