package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.dashboard.dto.CallAckResponse;
import com.boothlock.boothlock_server.dashboard.dto.CallRequest;
import com.boothlock.boothlock_server.dashboard.dto.CallResponse;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.global.error.CallCooldownException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** C6 직원 호출 생성·O15 확인 (명세서 C6·O15) */
@Service
public class CallService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final Duration COOLDOWN = Duration.ofSeconds(30);

    private final StaffCallRepository staffCallRepository;
    private final TableSessionRepository tableSessionRepository;
    private final BoothStaffAuthenticator staffAuthenticator;
    private final EntityManager entityManager;

    public CallService(StaffCallRepository staffCallRepository, TableSessionRepository tableSessionRepository,
            BoothStaffAuthenticator staffAuthenticator, EntityManager entityManager) {
        this.staffCallRepository = staffCallRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.staffAuthenticator = staffAuthenticator;
        this.entityManager = entityManager;
    }

    /** C6 — sessionId는 X-Session-Token 인증 계층(TableSessionAuthService)을 거친 값만 받는다 */
    @Transactional
    public CallResponse create(Long sessionId, CallRequest request) {
        // FOR UPDATE로 세션 row를 잠가 동시 요청을 직렬화한다 — 아래 쿨다운 조회→저장 사이 레이스 방지
        TableSessionEntity session = tableSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new NotFoundException("세션을 찾을 수 없습니다."));
        // 인증 계층이 같은 요청(open-in-view)에서 이미 이 세션을 올려 두었으면 잠금 조회는 그 사본을 그대로 돌려준다 —
        // 그 사이 커밋된 퇴실(O6)을 못 보고 종료된 세션에 호출이 붙는다. 잠근 채 다시 읽어 판정한다 (TableAdminService.lockBooth와 같은 이유)
        entityManager.refresh(session, LockModeType.PESSIMISTIC_WRITE);
        if (session.getEndedAt() != null) {
            throw new SessionExpiredException();
        }

        LocalDateTime now = LocalDateTime.now(KST);
        staffCallRepository.findFirstBySession_IdAndReasonOrderByCreatedAtDesc(sessionId, request.reason())
                .ifPresent(last -> {
                    Duration elapsed = Duration.between(last.getCreatedAt(), now);
                    if (elapsed.compareTo(COOLDOWN) < 0) {
                        throw new CallCooldownException(COOLDOWN.minus(elapsed).toSeconds());
                    }
                });

        StaffCallEntity call = new StaffCallEntity(session, request.reason(), now);
        staffCallRepository.save(call);

        return toResponse(call);
    }

    private CallResponse toResponse(StaffCallEntity call) {
        return new CallResponse(call.getId(), call.getReason(), call.getCreatedAt().atOffset(KST));
    }

    /** O15 — 부스는 JWT로만 정한다. 타 부스·미존재 호출은 구분 없이 404, 이미 확인된 호출은 그대로 200 (멱등) */
    @Transactional
    public CallAckResponse ack(String authorization, Long callId) {
        Long boothId = staffAuthenticator.authenticate(authorization).getBooth().getId();
        StaffCallEntity call = staffCallRepository.findByIdAndBoothId(callId, boothId)
                .orElseThrow(() -> new NotFoundException("호출을 찾을 수 없습니다."));
        call.ack();
        return new CallAckResponse(call.getId(), call.isAcked());
    }
}
