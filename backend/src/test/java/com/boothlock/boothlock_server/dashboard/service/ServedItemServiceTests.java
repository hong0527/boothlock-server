package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServedItemServiceTests {

    @Test
    void listRemovesEntriesOlderThanOneDayEvenWithoutAnotherToggle() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
        BoothStaffAuthenticator authenticator = mock(BoothStaffAuthenticator.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        BoothEntity booth = mock(BoothEntity.class);
        StaffAccountEntity staff = mock(StaffAccountEntity.class);
        OrderItemEntity item = mock(OrderItemEntity.class);
        OrderEntity order = new OrderEntity(1L, null, "M-1", java.time.LocalDate.now(), 1, null, 1000, true,
                java.time.LocalDateTime.now());
        order.addItem(item);

        when(booth.getId()).thenReturn(1L);
        when(staff.getBooth()).thenReturn(booth);
        when(authenticator.authenticate("Bearer token")).thenReturn(staff);
        when(item.getId()).thenReturn(10L);
        when(orderRepository.findByIdAndBoothId(20L, 1L)).thenReturn(Optional.of(order));

        ServedItemService service = new ServedItemService(authenticator, orderRepository, clock);
        service.setServed("Bearer token", 20L, 10L, true);

        clock.advance(ServedItemService.KEEP.plusSeconds(1));

        assertEquals(java.util.List.of(), service.list("Bearer token").orders());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
