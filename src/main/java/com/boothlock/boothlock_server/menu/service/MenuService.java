package com.boothlock.boothlock_server.menu.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.menu.domain.MenuEntity;
import com.boothlock.boothlock_server.menu.dto.MenuCreateResponse;
import com.boothlock.boothlock_server.menu.dto.MenuErrorResponse;
import com.boothlock.boothlock_server.menu.exception.MenuApiException;
import com.boothlock.boothlock_server.menu.repository.MenuRepository;
import com.boothlock.boothlock_server.order.service.MenuLookup;
import org.springframework.http.HttpStatus;
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
    public MenuCreateResponse create(String authorization, JsonNode request) {
        BoothEntity booth = authenticatedBooth(authorization);
        validateObject(request);

        List<MenuErrorResponse.FieldError> errors = new ArrayList<>();
        String name = requiredMenuName(request, errors);
        Integer price = requiredInt(request, "price", "가격은 0 이상이어야 합니다.", errors);
        String description = clearableText(request, "description", 200, "메뉴 설명은 200자 이하여야 합니다.", errors);
        String imageUrl = clearableText(request, "imageUrl", 500, "메뉴 이미지 URL은 500자 이하여야 합니다.", errors);
        Boolean visible = optionalBoolean(request, "visible", "visible은 boolean이어야 합니다.", errors);
        validateOptionalBoolean(request, "soldOut", "soldOut은 boolean이어야 합니다.", errors);

        if (price != null && price < 0) {
            errors.add(new MenuErrorResponse.FieldError("price", "가격은 0 이상이어야 합니다."));
        }
        throwInvalidIfAny(errors);
        if (menuRepository.existsByBooth_IdAndName(booth.getId(), name)) {
            throw new MenuApiException(HttpStatus.CONFLICT, "MENU_NAME_DUPLICATED", "동일한 메뉴명이 이미 존재합니다.");
        }

        MenuEntity menu = menuRepository.save(new MenuEntity(booth, name, price, imageUrl, description, visible == null || visible));
        return new MenuCreateResponse(menu.getId());
    }

    @Transactional
    public void update(String authorization, Long menuId, JsonNode request) {
        BoothEntity booth = authenticatedBooth(authorization);
        validateObject(request);
        validatePatchFields(request);

        MenuEntity menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new MenuApiException(HttpStatus.NOT_FOUND, "MENU_NOT_FOUND", "존재하지 않는 메뉴입니다."));
        if (!booth.getId().equals(menu.getBooth().getId())) {
            throw new MenuApiException(HttpStatus.FORBIDDEN, "MENU_FORBIDDEN", "해당 매장에 대한 권한이 없습니다.");
        }

        List<MenuErrorResponse.FieldError> errors = new ArrayList<>();
        String name = request.has("name") ? requiredMenuName(request, errors) : null;
        Integer price = request.has("price") ? requiredInt(request, "price", "가격은 0 이상이어야 합니다.", errors) : null;
        String description = request.has("description")
                ? clearableText(request, "description", 200, "메뉴 설명은 200자 이하여야 합니다.", errors) : null;
        String imageUrl = request.has("imageUrl")
                ? clearableText(request, "imageUrl", 500, "메뉴 이미지 URL은 500자 이하여야 합니다.", errors) : null;
        Boolean visible = request.has("visible") ? requiredBoolean(request, "visible", "visible은 boolean이어야 합니다.", errors) : null;
        Boolean soldOut = request.has("soldOut") ? requiredBoolean(request, "soldOut", "soldOut은 boolean이어야 합니다.", errors) : null;

        if (price != null && price < 0) {
            errors.add(new MenuErrorResponse.FieldError("price", "가격은 0 이상이어야 합니다."));
        }
        throwInvalidIfAny(errors);
        if (name != null && menuRepository.existsByBooth_IdAndNameAndIdNot(booth.getId(), name, menu.getId())) {
            throw new MenuApiException(HttpStatus.CONFLICT, "MENU_NAME_DUPLICATED", "동일한 메뉴명이 이미 존재합니다.");
        }

        if (request.has("name")) menu.updateName(name);
        if (request.has("price")) menu.updatePrice(price);
        if (request.has("description")) menu.updateDescription(description);
        if (request.has("imageUrl")) menu.updateImageUrl(imageUrl);
        if (request.has("visible")) menu.updateVisible(visible);
        if (request.has("soldOut")) menu.updateSoldOut(soldOut);
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
            throw invalid(List.of());
        }
    }

    private void validatePatchFields(JsonNode request) {
        if (request.isEmpty()) {
            throw new MenuApiException(HttpStatus.BAD_REQUEST, "MENU_NO_UPDATE_FIELD", "수정할 필드가 하나도 없습니다.");
        }
        request.propertyNames().forEach(field -> {
            if (!PATCH_FIELDS.contains(field)) {
                throw invalid(List.of(new MenuErrorResponse.FieldError(field, "지원하지 않는 필드입니다.")));
            }
        });
    }

    private String requiredMenuName(JsonNode request, List<MenuErrorResponse.FieldError> errors) {
        String message = "메뉴명은 1자 이상 50자 이하여야 합니다.";
        JsonNode node = request.get("name");
        if (node == null || !node.isString()) {
            errors.add(new MenuErrorResponse.FieldError("name", message));
            return null;
        }

        String value = node.asText().trim();
        if (value.isEmpty() || value.length() > 50) {
            errors.add(new MenuErrorResponse.FieldError("name", message));
            return null;
        }
        return value;
    }

    private String clearableText(JsonNode request, String field, int max, String message, List<MenuErrorResponse.FieldError> errors) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString() || node.asText().length() > max) {
            errors.add(new MenuErrorResponse.FieldError(field, message));
            return null;
        }
        return node.asText();
    }

    private Integer requiredInt(JsonNode request, String field, String message, List<MenuErrorResponse.FieldError> errors) {
        JsonNode node = request.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            errors.add(new MenuErrorResponse.FieldError(field, message));
            return null;
        }
        return node.asInt();
    }

    private Boolean optionalBoolean(JsonNode request, String field, String message, List<MenuErrorResponse.FieldError> errors) {
        JsonNode node = request.get(field);
        if (node == null) {
            return null;
        }
        if (!node.isBoolean()) {
            errors.add(new MenuErrorResponse.FieldError(field, message));
            return null;
        }
        return node.asBoolean();
    }

    private Boolean requiredBoolean(JsonNode request, String field, String message, List<MenuErrorResponse.FieldError> errors) {
        JsonNode node = request.get(field);
        if (node == null || !node.isBoolean()) {
            errors.add(new MenuErrorResponse.FieldError(field, message));
            return null;
        }
        return node.asBoolean();
    }

    private void validateOptionalBoolean(JsonNode request, String field, String message, List<MenuErrorResponse.FieldError> errors) {
        JsonNode node = request.get(field);
        if (node != null && !node.isBoolean()) {
            errors.add(new MenuErrorResponse.FieldError(field, message));
        }
    }

    private void throwInvalidIfAny(List<MenuErrorResponse.FieldError> errors) {
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
    }

    private MenuApiException invalid(List<MenuErrorResponse.FieldError> errors) {
        return new MenuApiException(HttpStatus.BAD_REQUEST, "MENU_INVALID_INPUT", "입력값이 올바르지 않습니다.", errors);
    }
}
