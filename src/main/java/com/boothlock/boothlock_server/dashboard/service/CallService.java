package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.dashboard.domain.StaffCallEntity;
import com.boothlock.boothlock_server.dashboard.dto.CallRequest;
import com.boothlock.boothlock_server.dashboard.dto.CallResponse;
import com.boothlock.boothlock_server.dashboard.repository.StaffCallRepository;
import com.boothlock.boothlock_server.global.error.CallCooldownException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;

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

    public CallService(StaffCallRepository staffCallRepository, TableSessionRepository tableSessionRepository) {
        this.staffCallRepository = staffCallRepository;
        this.tableSessionRepository = tableSessionRepository;
    }

    @Transactional
    public CallResponse create(Long sessionId, CallRequest request) {
        TableSessionEntity session = tableSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("세션을 찾을 수 없습니다."));
        if (session.getEndedAt() != null) {
            throw new SessionExpiredException();
        }

        LocalDateTime now = LocalDateTime.now(KST);
        staffCallRepository.findFirstBySession_IdOrderByCreatedAtDesc(sessionId)
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

    @Transactional
    public void ack(Long callId) {
        StaffCallEntity call = staffCallRepository.findById(callId)
                .orElseThrow(() -> new NotFoundException("호출을 찾을 수 없습니다."));
        call.ack();
    }
}
