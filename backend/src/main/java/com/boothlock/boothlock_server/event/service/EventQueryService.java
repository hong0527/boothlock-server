package com.boothlock.boothlock_server.event.service;

import com.boothlock.boothlock_server.event.domain.EventMapEntity;
import com.boothlock.boothlock_server.event.dto.BoothListResponse;
import com.boothlock.boothlock_server.event.dto.EventMapResponse;
import com.boothlock.boothlock_server.event.repository.BoothSeatRepository;
import com.boothlock.boothlock_server.event.repository.BoothSeatRow;
import com.boothlock.boothlock_server.event.repository.EventMapRepository;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.seat.SeatIdlePolicy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 손님 홈 화면 — 축제장 입구·포스터의 대표 QR로 들어오는 공개 화면 (명세서 E1·E2).
 * 인증이 없으므로 응답 DTO에 담긴 것 외에는 어떤 값도 밖으로 내보내지 않는다 (§7-18).
 */
@Service
public class EventQueryService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final BoothSeatRepository boothSeatRepository;
    private final EventMapRepository eventMapRepository;
    private final SeatIdlePolicy seatIdlePolicy;
    private final long cacheTtlNanos;
    private final LongSupplier nanoClock;
    private final Object refreshLock = new Object();
    private volatile CachedBooths cachedBooths;

    /** 캐시 스냅샷 — 한 객체로 묶어 교체해야 목록과 만료 시각이 어긋난 채로 읽히지 않는다 */
    private record CachedBooths(List<BoothListResponse.Booth> booths, long expiresAtNanos) {
    }

    /** 유휴 임계값과 그 하한 검증은 SeatIdlePolicy가 맡는다 — 운영자 좌석 현황(O3)·세션 발급(C1)과 같은 기준을 쓰기 위해서다 */
    @Autowired
    public EventQueryService(BoothSeatRepository boothSeatRepository,
                             EventMapRepository eventMapRepository,
                             SeatIdlePolicy seatIdlePolicy,
                             // 명세 §E1·§7-19의 서버 캐시. 0이면 끈다
                             @Value("${boothlock.event.booths-cache-seconds:10}") long cacheSeconds) {
        this(boothSeatRepository, eventMapRepository, seatIdlePolicy, cacheSeconds, System::nanoTime);
    }

    /** 캐시 만료 경계를 테스트에서 시계를 돌려 확인하기 위한 생성자 */
    EventQueryService(BoothSeatRepository boothSeatRepository,
                      EventMapRepository eventMapRepository,
                      SeatIdlePolicy seatIdlePolicy,
                      long cacheSeconds,
                      LongSupplier nanoClock) {
        if (cacheSeconds < 0) {
            throw new IllegalArgumentException(
                    "boothlock.event.booths-cache-seconds는 0 이상이어야 합니다. 현재 값: " + cacheSeconds);
        }
        this.cacheTtlNanos = Duration.ofSeconds(cacheSeconds).toNanos();
        this.nanoClock = nanoClock;
        this.boothSeatRepository = boothSeatRepository;
        this.eventMapRepository = eventMapRepository;
        this.seatIdlePolicy = seatIdlePolicy;
    }

    /**
     * E1 — 부스 목록과 좌석 현황.
     *
     * <p>전체 목록을 {@code booths-cache-seconds}(기본 10초) 동안 재사용한다 (명세 §E1·§7-19).
     * 인증 없는 폴링 API라, 캐시가 없으면 누구든 반복 호출로 좌석 집계 쿼리를 계속 돌리게 할 수 있고
     * 그 쿼리가 DB 커넥션 풀을 주문 API와 나눠 쓴다. 캐시 없이 동시 50 요청을 넣었을 때 주문 조회까지
     * 2ms에서 400ms로 느려지는 것을 실측했다. 캐시가 살아 있는 동안에는 DB에 가지 않는다.
     * 대가로 좌석 수와 주문 접수 여부가 최대 10초 늦게 반영된다 — 명세가 허용한 범위다.
     *
     * <p>이 메서드에 트랜잭션을 걸지 않는다. 걸면 캐시 적중 때도 트랜잭션이 커넥션을 잡아
     * 캐시를 둔 의미가 반감된다. 집계 쿼리는 엔티티를 읽지 않는 프로젝션이라 트랜잭션 없이도 안전하다.
     *
     * <p>카테고리 필터는 캐시된 전체 목록에서 메모리로 건다 — 카테고리마다 캐시를 따로 두지 않기 위해서다.
     * 대소문자는 무시하고, 비어 있으면 전체, 모르는 값이면 빈 목록이다 (400이 아니다).
     */
    public BoothListResponse getBooths(String category) {
        List<BoothListResponse.Booth> booths = allBooths().stream()
                .filter(booth -> category == null || category.isBlank() || category.equalsIgnoreCase(booth.category()))
                .toList();
        return new BoothListResponse(booths);
    }

    private List<BoothListResponse.Booth> allBooths() {
        if (cacheTtlNanos == 0) {
            return loadBooths();
        }
        CachedBooths snapshot = cachedBooths;
        if (snapshot != null && nanoClock.getAsLong() < snapshot.expiresAtNanos()) {
            return snapshot.booths();
        }
        // 만료 순간 몰린 요청이 전부 집계 쿼리를 돌리지 않도록 한 번만 새로 읽는다
        synchronized (refreshLock) {
            snapshot = cachedBooths;
            if (snapshot != null && nanoClock.getAsLong() < snapshot.expiresAtNanos()) {
                return snapshot.booths();
            }
            List<BoothListResponse.Booth> fresh = loadBooths();
            cachedBooths = new CachedBooths(fresh, nanoClock.getAsLong() + cacheTtlNanos);
            return fresh;
        }
    }

    private List<BoothListResponse.Booth> loadBooths() {
        SeatIdlePolicy.Criteria criteria = seatIdlePolicy.criteria();
        return boothSeatRepository.findSeatSummaries(criteria.idleSince(), criteria.businessDate()).stream()
                .map(EventQueryService::toBooth)
                .toList();
    }

    private static BoothListResponse.Booth toBooth(BoothSeatRow row) {
        // 좌표는 둘 다 있어야 지도에 찍을 수 있다 — 한쪽만 있으면 없는 것으로 준다
        boolean positioned = row.getMapX() != null && row.getMapY() != null;
        return new BoothListResponse.Booth(
                row.getBoothId(),
                row.getName(),
                row.getCategory(),
                row.isOpen(),
                positioned ? row.getMapX() : null,
                positioned ? row.getMapY() : null,
                new BoothListResponse.Tables(row.getTotalTables(), row.getEmptyTables()));
    }

    /** E2 — 행사 약도. 등록 전이면 404이고 클라이언트는 지도 없이 목록만 보여준다 */
    @Transactional(readOnly = true)
    public EventMapResponse getMap() {
        EventMapEntity map = eventMapRepository.findFirstByOrderByIdDesc()
                .orElseThrow(() -> new NotFoundException("등록된 행사 약도가 없습니다."));
        return new EventMapResponse(
                map.getImageUrl(), map.getWidth(), map.getHeight(), map.getUpdatedAt().atOffset(KST));
    }
}
