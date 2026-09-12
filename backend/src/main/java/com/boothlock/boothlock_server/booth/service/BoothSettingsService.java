package com.boothlock.boothlock_server.booth.service;

import com.boothlock.boothlock_server.booth.domain.BoothAccountChangeLogEntity;
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
        if (request.has("bankAccount")) changeBankAccount(request, staff, booth);

        return new BoothInfoDto.Response(booth.getName(), booth.getBankAccount(), booth.getOperatingHours(),
                boothRepository.countTablesByBoothId(booth.getId()), booth.isOpen());
    }

    private void changeBankAccount(JsonNode request, StaffAccountEntity staff, BoothEntity booth) {
        if (staff.getRole() != StaffRole.ADMIN) throw new ForbiddenException();
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

    private void validateFields(JsonNode request) {
        request.propertyNames().forEach(name -> {
            if (!name.equals("name") && !name.equals("operatingHours")
                    && !name.equals("isOpen") && !name.equals("bankAccount"))
                throw new InvalidRequestException("지원하지 않는 필드입니다: " + name);
        });
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
