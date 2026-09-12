package com.boothlock.boothlock_server.menu.dto;

import com.boothlock.boothlock_server.menu.domain.MenuEntity;

public record MenuResponse(
        Long id,
        String name,
        int price,
        String imageUrl,
        String description,
        boolean soldOut,
        boolean visible
) {
    public static MenuResponse from(MenuEntity menu) {
        return new MenuResponse(
                menu.getId(),
                menu.getName(),
                menu.getPrice(),
                menu.getImageUrl(),
                menu.getDescription(),
                menu.isSoldOut(),
                menu.isVisible()
        );
    }
}
