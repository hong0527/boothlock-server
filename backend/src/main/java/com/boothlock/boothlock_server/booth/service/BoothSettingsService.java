package com.boothlock.boothlock_server.booth.service;

import com.boothlock.boothlock_server.booth.domain.BoothAccountChangeLogEntity;
import com.boothlock.boothlock_server.booth.domain.BoothCategory;
import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.domain.StaffRole;
import com.boothlock.boothlock_server.booth.dto.BoothInfoDto;
import com.boothlock.boothlock_server.booth.repository.BoothAccountChangeLogRepository;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Service
public class BoothSettingsService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 약도 상대 좌표 상한 — 10000 = 100.00% (명세 E1) */
    public static final int MAP_COORDINATE_MAX = 10_000;
    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final BoothRepository boothRepository;
    private final BoothAccountChangeLogRepository changeLogRepository;
    private final BoothWebhookNotifier webhookNotifier;

    public BoothSettingsService(BoothJwtProvider jwtProvider, BoothInfoService boothInfoService,
            BoothRepository boothRepository, BoothAccountChangeLogRepository changeLogRepository,
            BoothWebhookNotifier webhookNotifier) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.boothRepository = boothRepository;
        this.changeLogRepository = changeLogRepository;
        this.webhookNotifier = webhookNotifier;
    }

    @Transactional
    public BoothInfoDto.Response update(String authorization, JsonNode request) {
        // 인증을 맨 앞에 — 만료·위조 토큰에 본문 검증 규칙이 노출되지 않게 한다
        StaffAccountEntity staff = boothInfoService.authenticate(jwtProvider.verify(authorization));
        // booth_id는 NULL 허용 컬럼 — 무소속 계정은 부스 설정을 만질 수 없다 (O16과 동일 처리)
        BoothEntity staffBooth = staff.getBooth();
        if (staffBooth == null) throw new ForbiddenException();

        if (request == null || !request.isObject() || request.isEmpty())
            throw new InvalidRequestException("변경할 필드를 1개 이상 보내야 합니다.");
        validateFields(request);
        BoothEntity booth = boothRepository.findById(staffBooth.getId())
                .orElseThrow(() -> new NotFoundException("부스를 찾을 수 없습니다."));

        if (request.has("name")) booth.updateName(requiredText(request, "name", 50));
        if (request.has("operatingHours")) {
            booth.updateOperatingHours(request.get("operatingHours").isNull() ? null
                    : optionalText(request, "operatingHours", 50));
        }
        if (request.has("isOpen")) {
            if (!request.get("isOpen").isBoolean()) throw new InvalidRequestException("isOpen은 boolean이어야 합니다.");
            booth.updateOpen(request.get("isOpen").asBoolean());
        }
        if (request.has("category")) booth.updateCategory(category(request));
        if (request.has("mapX") || request.has("mapY")) {
            // 반쪽 좌표는 지도에 찍을 수 없다 — 한쪽만 오면 저장된 다른 한쪽과 섞지 않고 거부한다 (명세 O17)
            if (!request.has("mapX") || !request.has("mapY"))
                throw new InvalidRequestException("mapX와 mapY는 함께 보내야 합니다.");
            booth.updateMapPosition(coordinate(request, "mapX"), coordinate(request, "mapY"));
        }
        if (request.has("bankAccount")) changeBankAccount(request, staff, booth);
        if (request.has("depositorName")) changeDepositorName(request, staff, booth);

        return BoothInfoService.toResponse(booth, boothRepository.countTablesByBoothId(booth.getId()));
    }

    private void changeBankAccount(JsonNode request, StaffAccountEntity staff, BoothEntity booth) {
        requireAdmin(staff);
        String newValue = requiredText(request, "bankAccount", 100);
        String oldValue = booth.getBankAccount();
        if (Objects.equals(oldValue, newValue)) return;
        LocalDateTime changedAt = LocalDateTime.now(KST);
        booth.updateBankAccount(newValue);
        changeLogRepository.save(new BoothAccountChangeLogEntity(
                booth, staff.getLoginId(), changedAt, oldValue, newValue));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                webhookNotifier.notifyBankAccountChanged(booth.getId(), staff.getLoginId(), changedAt);
            }
        });
    }

    // 계좌 화면의 일부지만 표시용 라벨일 뿐 실제 입금 경로(계좌번호)를 바꾸지 않아 bankAccount와 달리
    // 감사 로그·웹훅 대상은 아니다. 다만 같은 화면·같은 신뢰 등급이라 ADMIN 권한은 동일하게 요구한다.
    private void changeDepositorName(JsonNode request, StaffAccountEntity staff, BoothEntity booth) {
        requireAdmin(staff);
        JsonNode node = request.get("depositorName");
        if (node.isNull()) {
            booth.updateDepositorName(null);
            return;
        }
        // 빈 문자열/공백도 null(미지정)로 취급 — "null = 미지정" 계약을 공백 값이 조용히 깨지 않게 한다
        String value = optionalText(request, "depositorName", 50).trim();
        booth.updateDepositorName(value.isEmpty() ? null : value);
    }

    private void requireAdmin(StaffAccountEntity staff) {
        if (staff.getRole() != StaffRole.ADMIN) throw new ForbiddenException();
    }

    private void validateFields(JsonNode request) {
        request.propertyNames().forEach(name -> {
            if (!name.equals("name") && !name.equals("operatingHours") && !name.equals("isOpen")
                    && !name.equals("bankAccount") && !name.equals("depositorName")
                    && !name.equals("category") && !name.equals("mapX") && !name.equals("mapY"))
                throw new InvalidRequestException("지원하지 않는 필드입니다: " + name);
        });
    }

    /**
     * null로 지우기는 받지 않는다. 분류를 모르겠으면 ETC가 있고, 시딩으로 넣은 값을
     * 폼이 기본값 null을 실어 보내는 실수로 날리면 E1 필터에서 부스가 조용히 빠진다.
     */
    private String category(JsonNode request) {
        JsonNode node = request.get("category");
        if (node == null || !node.isString() || !BoothCategory.isValid(node.asText()))
            throw new InvalidRequestException("category는 FOOD, CAFE, GOODS, ETC 중 하나여야 합니다.");
        return node.asText();
    }

    /**
     * 0~10000 정수만 — 소수(3200.5)·문자열("3200")·null은 거부한다 (명세 E1 좌표 규칙, §7-20).
     * null로 핀 제거도 받지 않는다: 좌표는 주최측이 약도를 보고 시딩한 값이라, 운영자 화면에서 지우면
     * 다시 찍을 기준이 없고 손님 지도에서 부스가 사라진다. 운영자는 옮기기(미세 조정)만 한다.
     */
    private int coordinate(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()
                || node.intValue() < 0 || node.intValue() > MAP_COORDINATE_MAX)
            throw new InvalidRequestException(field + "는 0~" + MAP_COORDINATE_MAX + " 정수여야 합니다.");
        return node.intValue();
    }

    private String requiredText(JsonNode request, String field, int max) {
        String value = optionalText(request, field, max);
        if (value.isBlank()) throw new InvalidRequestException(field + "는 빈 값일 수 없습니다.");
        return value;
    }

    private String optionalText(JsonNode request, String field, int max) {
        JsonNode node = request.get(field);
        if (node == null || !node.isString()) throw new InvalidRequestException(field + "는 문자열이어야 합니다.");
        String value = node.asText();
        if (value.length() > max) throw new InvalidRequestException(field + "는 " + max + "자 이하여야 합니다.");
        return value;
    }
}
