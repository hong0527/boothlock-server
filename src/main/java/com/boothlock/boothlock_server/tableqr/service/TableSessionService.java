package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionCreateRequest;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionResponse;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.support.SecureTokenGenerator;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * C1 세션 발급 — QR 토큰 검증 → 활성 세션 있으면 복원(restored:true), 없으면 생성+테이블 사용중 전환 (명세서 C1).
 * 클래스에 @Transactional을 걸지 않는다: 동시 스캔의 유니크 제약 위반 복구가 트랜잭션 밖에서만 가능하기 때문
 * (OrderCreateService와 동일한 이유 — TableSessionWriter 주석 참조).
 */
@Service
public class TableSessionService {

    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

    private final TableRepository tableRepository;
    private final TableSessionRepository tableSessionRepository;
    private final TableSessionWriter tableSessionWriter;

    public TableSessionService(TableRepository tableRepository,
                                TableSessionRepository tableSessionRepository,
                                TableSessionWriter tableSessionWriter) {
        this.tableRepository = tableRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.tableSessionWriter = tableSessionWriter;
    }

    public TableSessionResponse createOrRestore(TableSessionCreateRequest request) {
        if (request == null || request.tableToken() == null || request.tableToken().isBlank()) {
            throw new InvalidRequestException("tableToken이 필요합니다.");
        }

        // booth를 함께 가져온다 — 응답 조립이 이 트랜잭션 밖(메서드 종료 후)에서 이뤄져 LAZY 접근이 불가능하다
        TableEntity table = tableRepository.findByTableTokenWithBooth(request.tableToken())
                .orElseThrow(() -> new NotFoundException("유효하지 않은 QR입니다."));

        TableSessionEntity restored = restoreActiveSession(table.getId());
        if (restored != null) {
            return toResponse(table, restored, true);
        }

        String sessionToken = SecureTokenGenerator.generate();
        LocalDateTime now = LocalDateTime.now(KST_ZONE);
        try {
            TableSessionEntity created = tableSessionWriter.createSession(table.getId(), sessionToken, now);
            return toResponse(table, created, false);
        } catch (DataIntegrityViolationException e) {
            // 동시 스캔 레이스 — 저장 트랜잭션이 끝난 뒤라 여기서는 재조회가 안전하다 (DB스키마 §1 table_session 주석)
            TableSessionEntity winner = restoreActiveSession(table.getId());
            if (winner != null) {
                return toResponse(table, winner, true);
            }
            throw e;
        }
    }

    private TableSessionEntity restoreActiveSession(Long tableId) {
        return tableSessionRepository.findByTableIdAndEndedAtIsNull(tableId)
                .map(session -> {
                    session.touch(LocalDateTime.now(KST_ZONE));
                    return tableSessionRepository.save(session);
                })
                .orElse(null);
    }

    private TableSessionResponse toResponse(TableEntity table, TableSessionEntity session, boolean restored) {
        return new TableSessionResponse(
                session.getSessionToken(),
                new TableSessionResponse.Booth(table.getBooth().getName(), table.getBooth().isOpen()),
                new TableSessionResponse.Table(table.getLabel()),
                restored);
    }
}
