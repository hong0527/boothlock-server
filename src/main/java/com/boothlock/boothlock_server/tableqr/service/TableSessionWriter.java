package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 세션 생성 + 테이블 사용중 전환만 담당하는 쓰기 경계 — 별도 빈으로 둔 이유가 있다.
 * 같은 QR 동시 스캔은 활성 세션 유니크 제약(uq_session_active) 위반으로 이 트랜잭션이 롤백되는데,
 * 같은 트랜잭션 안에서는 재조회조차 할 수 없다(OrderWriter와 동일한 이유).
 * 호출자(TableSessionService)가 트랜잭션 밖에서 예외를 받아 새 트랜잭션으로 복구하도록 경계를 여기서 끊는다.
 */
@Component
public class TableSessionWriter {

    private final TableRepository tableRepository;
    private final TableSessionRepository tableSessionRepository;

    public TableSessionWriter(TableRepository tableRepository, TableSessionRepository tableSessionRepository) {
        this.tableRepository = tableRepository;
        this.tableSessionRepository = tableSessionRepository;
    }

    @Transactional
    public TableSessionEntity createSession(Long tableId, String sessionToken, LocalDateTime now) {
        TableEntity table = tableRepository.findById(tableId)
                .orElseThrow(() -> new NotFoundException("유효하지 않은 QR입니다."));
        TableSessionEntity session = tableSessionRepository.saveAndFlush(
                new TableSessionEntity(table, sessionToken, now));
        table.occupy();
        return session;
    }
}
