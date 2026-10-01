package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.dashboard.dto.ServedItemsResponse;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주문현황 진행 카드의 메뉴별 "나감" 체크 (명세서 밖) — 한 주문이 나눠 나갈 때(콜라 먼저, 삼겹살 나중) 뭐가 남았는지
 * 같은 부스의 여러 기기가 함께 보게 한다.
 *
 * <p><b>DB에 저장하지 않고 서버 메모리에만 둔다</b> — 운영자 손 체크용 메모라 주문 상태·정산과 무관하고, 스키마를 바꾸면
 * 운영 RDS(ddl-auto=validate)에 수동 ALTER가 필요해진다. 대가로 API 재시작(배포)하면 체크가 모두 사라진다.
 * 단일 EC2·API 컨테이너 1개 배포라 인스턴스 간 공유 문제는 없다.
 *
 * <p>부스는 JWT로만 정하고, 주문·항목은 그 부스의 것인지 확인한다(타 부스 주문은 404 — 다른 대시보드 API와 같은 존재 은닉).
 * 오래된 기록이 쌓이지 않게 쓸 때마다 {@link #KEEP}보다 오래된 주문 기록을 지운다.
 */
@Service
public class ServedItemService {

    static final Duration KEEP = Duration.ofHours(24);

    private record Entry(Set<Long> itemIds, Instant updatedAt) {
    }

    /** boothId → (orderId → 나간 항목) */
    private final Map<Long, Map<Long, Entry>> byBooth = new ConcurrentHashMap<>();

    private final BoothStaffAuthenticator staffAuthenticator;
    private final OrderRepository orderRepository;
    private final Clock clock;

    public ServedItemService(BoothStaffAuthenticator staffAuthenticator, OrderRepository orderRepository) {
        this(staffAuthenticator, orderRepository, Clock.systemUTC());
    }

    /** 테스트에서는 시간을 고정해 만료 정리를 검증한다. */
    ServedItemService(BoothStaffAuthenticator staffAuthenticator, OrderRepository orderRepository, Clock clock) {
        this.staffAuthenticator = staffAuthenticator;
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    /** 부스의 체크 전체 — 주문현황 폴링이 함께 부른다 */
    public ServedItemsResponse list(String authorization) {
        Long boothId = staffAuthenticator.authenticate(authorization).getBooth().getId();
        Map<Long, Entry> orders = byBooth.getOrDefault(boothId, Map.of());
        removeExpired(orders, clock.instant());
        return new ServedItemsResponse(orders.entrySet().stream()
                .map(e -> toOrderServed(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(ServedItemsResponse.OrderServed::orderId))
                .toList());
    }

    /** 항목 하나 체크/해제. 같은 값을 다시 보내도 결과가 같다(멱등) — 두 기기가 동시에 눌러도 마지막 값이 남는다 */
    @Transactional(readOnly = true)
    public ServedItemsResponse.OrderServed setServed(String authorization, Long orderId, Long itemId, boolean served) {
        Long boothId = staffAuthenticator.authenticate(authorization).getBooth().getId();
        OrderEntity order = orderRepository.findByIdAndBoothId(orderId, boothId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다."));
        boolean itemInOrder = order.getItems().stream().anyMatch(item -> item.getId().equals(itemId));
        if (!itemInOrder) {
            throw new NotFoundException("주문 항목을 찾을 수 없습니다.");
        }

        Instant now = clock.instant();
        Map<Long, Entry> orders = byBooth.computeIfAbsent(boothId, id -> new ConcurrentHashMap<>());
        removeExpired(orders, now);
        Entry updated = orders.compute(orderId, (id, prev) -> {
            Set<Long> ids = prev == null ? new HashSet<>() : new HashSet<>(prev.itemIds());
            if (served) ids.add(itemId);
            else ids.remove(itemId);
            return ids.isEmpty() ? null : new Entry(Set.copyOf(ids), now);
        });
        return updated == null ? new ServedItemsResponse.OrderServed(orderId, List.of()) : toOrderServed(orderId, updated);
    }

    private ServedItemsResponse.OrderServed toOrderServed(Long orderId, Entry entry) {
        return new ServedItemsResponse.OrderServed(orderId, entry.itemIds().stream().sorted().toList());
    }

    /** 조회만 계속해도 만료 기록이 응답과 메모리에서 사라지게 한다. */
    private void removeExpired(Map<Long, Entry> orders, Instant now) {
        orders.values().removeIf(entry -> entry.updatedAt().isBefore(now.minus(KEEP)));
    }
}
