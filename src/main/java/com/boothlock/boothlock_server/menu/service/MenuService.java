package com.boothlock.boothlock_server.menu.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.dto.MenuResponse;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.order.service.MenuLookup;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Service
public class MenuService implements MenuLookup {

    private static final Set<String> PATCH_FIELDS = Set.of("name", "price", "description", "imageUrl", "visible", "soldOut");

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final MenuRepository menuRepository;

    public MenuService(BoothJwtProvider jwtProvider, BoothInfoService boothInfoService, MenuRepository menuRepository) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.menuRepository = menuRepository;
    }

    @Transactional
    public MenuResponse create(String authorization, JsonNode request) {
        BoothEntity booth = authenticatedBooth(authorization);
        validateObject(request);

        List<String> errors = new ArrayList<>();
        String name = requiredMenuName(request, errors);
        Integer price = requiredInt(request, "price", "가격은 0 이상이어야 합니다.", errors);
        String description = clearableText(request, "description", 200, "메뉴 설명은 200자 이하여야 합니다.", errors);
        String imageUrl = clearableText(request, "imageUrl", 500, "메뉴 이미지 URL은 500자 이하여야 합니다.", errors);
        Boolean visible = optionalBoolean(request, "visible", "visible은 boolean이어야 합니다.", errors);
        validateOptionalBoolean(request, "soldOut", "soldOut은 boolean이어야 합니다.", errors);

        if (price != null && price < 0) {
            errors.add("price: 가격은 0 이상이어야 합니다.");
        }
        throwInvalidIfAny(errors);
        if (menuRepository.existsByBooth_IdAndName(booth.getId(), name)) {
            throw duplicatedMenuName();
        }

        try {
            MenuEntity menu = menuRepository.saveAndFlush(new MenuEntity(booth, name, price, imageUrl, description, visible == null || visible));
            return MenuResponse.from(menu);
        } catch (DataIntegrityViolationException exception) {
            throw duplicatedMenuName();
        }
    }

    @Transactional
    public MenuResponse update(String authorization, Long menuId, JsonNode request) {
        BoothEntity booth = authenticatedBooth(authorization);
        validateObject(request);
        validatePatchFields(request);

        MenuEntity menu = menuRepository.findByIdAndBooth_Id(menuId, booth.getId())
                .orElseThrow(() -> new NotFoundException("존재하지 않는 메뉴입니다."));

        List<String> errors = new ArrayList<>();
        String name = request.has("name") ? requiredMenuName(request, errors) : null;
        Integer price = request.has("price") ? requiredInt(request, "price", "가격은 0 이상이어야 합니다.", errors) : null;
        String description = request.has("description")
                ? clearableText(request, "description", 200, "메뉴 설명은 200자 이하여야 합니다.", errors) : null;
        String imageUrl = request.has("imageUrl")
                ? clearableText(request, "imageUrl", 500, "메뉴 이미지 URL은 500자 이하여야 합니다.", errors) : null;
        Boolean visible = request.has("visible") ? requiredBoolean(request, "visible", "visible은 boolean이어야 합니다.", errors) : null;
        Boolean soldOut = request.has("soldOut") ? requiredBoolean(request, "soldOut", "soldOut은 boolean이어야 합니다.", errors) : null;

        if (price != null && price < 0) {
            errors.add("price: 가격은 0 이상이어야 합니다.");
        }
        throwInvalidIfAny(errors);
        if (name != null && menuRepository.existsByBooth_IdAndNameAndIdNot(booth.getId(), name, menu.getId())) {
            throw duplicatedMenuName();
        }

        if (request.has("name")) menu.updateName(name);
        if (request.has("price")) menu.updatePrice(price);
        if (request.has("description")) menu.updateDescription(description);
        if (request.has("imageUrl")) menu.updateImageUrl(imageUrl);
        if (request.has("visible")) menu.updateVisible(visible);
        if (request.has("soldOut")) menu.updateSoldOut(soldOut);
        try {
            menuRepository.flush();
            return MenuResponse.from(menu);
        } catch (DataIntegrityViolationException exception) {
            throw duplicatedMenuName();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<MenuInfo> findByBoothIdAndMenuIds(Long boothId, Collection<Long> menuIds) {
        if (boothId == null || menuIds == null || menuIds.isEmpty()) {
            return List.of();
        }
        return menuRepository.findByBooth_IdAndIdIn(boothId, menuIds).stream()
                .map(menu -> new MenuInfo(menu.getId(), menu.getName(), menu.getPrice(), menu.isSoldOut(), menu.isVisible()))
                .toList();
    }

    private BoothEntity authenticatedBooth(String authorization) {
        StaffAccountEntity staff = boothInfoService.authenticate(jwtProvider.verify(authorization));
        if (staff.getBooth() == null) {
        throw new ForbiddenException();
        }
        return staff.getBooth();
    }

    private void validateObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw invalid(List.of("요청 본문은 JSON 객체여야 합니다."));
        }
    }

    private void validatePatchFields(JsonNode request) {
        if (request.isEmpty()) {
            throw new InvalidRequestException("수정할 필드가 하나도 없습니다.");
        }
        request.propertyNames().forEach(field -> {
            if (!PATCH_FIELDS.contains(field)) {
                throw invalid(List.of(field + ": 지원하지 않는 필드입니다."));
            }
        });
    }

    private String requiredMenuName(JsonNode request, List<String> errors) {
        String message = "메뉴명은 1자 이상 50자 이하여야 합니다.";
        JsonNode node = request.get("name");
        if (node == null || !node.isString()) {
            errors.add("name: " + message);
            return null;
        }

        String value = node.asText().trim();
        if (value.isEmpty() || value.length() > 50) {
            errors.add("name: " + message);
            return null;
        }
        return value;
    }

    private String clearableText(JsonNode request, String field, int max, String message, List<String> errors) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            errors.add(field + ": " + message);
            return null;
        }
        String value = node.asText().trim();
        if (value.length() > max) {
            errors.add(field + ": " + message);
            return null;
        }
        return value;
    }

    private Integer requiredInt(JsonNode request, String field, String message, List<String> errors) {
        JsonNode node = request.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            errors.add(field + ": " + message);
            return null;
        }
        return node.asInt();
    }

    private Boolean optionalBoolean(JsonNode request, String field, String message, List<String> errors) {
        JsonNode node = request.get(field);
        if (node == null) {
            return null;
        }
        if (!node.isBoolean()) {
            errors.add(field + ": " + message);
            return null;
        }
        return node.asBoolean();
    }

    private Boolean requiredBoolean(JsonNode request, String field, String message, List<String> errors) {
        JsonNode node = request.get(field);
        if (node == null || !node.isBoolean()) {
            errors.add(field + ": " + message);
            return null;
        }
        return node.asBoolean();
    }

    private void validateOptionalBoolean(JsonNode request, String field, String message, List<String> errors) {
        JsonNode node = request.get(field);
        if (node != null && !node.isBoolean()) {
            errors.add(field + ": " + message);
        }
    }

    private void throwInvalidIfAny(List<String> errors) {
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
    }

    private InvalidRequestException invalid(List<String> errors) {
        String details = String.join(" ", errors);
        return new InvalidRequestException("입력값이 올바르지 않습니다." + (details.isBlank() ? "" : " " + details));
    }

    private InvalidStateException duplicatedMenuName() {
        return new InvalidStateException("동일한 메뉴명이 이미 존재합니다.");
    }
}
