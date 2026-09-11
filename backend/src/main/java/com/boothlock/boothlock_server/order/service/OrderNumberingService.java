package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.order.repository.DailyCounterRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 채번 (명세서 §2). auto-increment 금지 — 카운터 행을 FOR UPDATE로 잠그고 +1 */
@Service
public class OrderNumberingService {

    private final DailyCounterRepository dailyCounterRepository;

    public OrderNumberingService(DailyCounterRepository dailyCounterRepository) {
        this.dailyCounterRepository = dailyCounterRepository;
    }

    /** 영업일 = (시각 − 6h)의 날짜. 06:00~익일 05:59 KST가 한 영업일 (명세서 §2) */
    public LocalDate businessDateOf(LocalDateTime at) {
        return at.minusHours(6).toLocalDate();
    }

    /** 다음 순번 발급 — 주문 INSERT와 같은 트랜잭션에서만 호출한다 (잠금 수명 = 트랜잭션) */
    @Transactional(propagation = Propagation.MANDATORY)
    public int nextSeq(Long boothId, LocalDate businessDate) {
        // readOnly 트랜잭션은 flush를 하지 않아 last_seq 증가가 조용히 사라진다 — MANDATORY로는 못 막는다
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("채번은 읽기 전용 트랜잭션에서 호출할 수 없습니다");
        }
        dailyCounterRepository.ensureCounter(boothId, businessDate);
        return dailyCounterRepository
                .findWithLockByBoothIdAndBusinessDate(boothId, businessDate)
                .orElseThrow(() -> new IllegalStateException(
                        "채번 카운터 조회 실패 boothId=" + boothId + " businessDate=" + businessDate))
                .nextSeq();
    }
}
