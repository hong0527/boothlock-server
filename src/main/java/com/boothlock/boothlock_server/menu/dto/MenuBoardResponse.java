package com.boothlock.boothlock_server.menu.dto;

import com.boothlock.boothlock_server.menu.domain.MenuEntity;

import java.util.List;

public record MenuBoardResponse(
        String boothName,
        boolean isOpen,
        List<MenuItem> menus
) {
    public record MenuItem(
            Long id,
            String name,
            int price,
            String imageUrl,
            String description,
            boolean soldOut
    ) {
        public static MenuItem from(MenuEntity menu) {
            return new MenuItem(
                    menu.getId(),
                    menu.getName(),
                    menu.getPrice(),
                    menu.getImageUrl(),
                    menu.getDescription(),
                    menu.isSoldOut()
            );
        }
    }
}
