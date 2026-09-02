package com.boothlock.boothlock_server.menu.repository;

import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MenuRepository extends JpaRepository<MenuEntity, Long> {

    boolean existsByBooth_IdAndName(Long boothId, String name);

    boolean existsByBooth_IdAndNameAndIdNot(Long boothId, String name, Long id);

    Optional<MenuEntity> findByIdAndBooth_Id(Long id, Long boothId);

    List<MenuEntity> findByBooth_IdAndVisibleTrue(Long boothId);

    List<MenuEntity> findByBooth_IdAndIdIn(Long boothId, Collection<Long> ids);
}
