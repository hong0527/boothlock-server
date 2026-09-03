package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.dto.TableAdminResponse;
import com.boothlock.boothlock_server.tableqr.dto.TableBulkCreateRequest;
import com.boothlock.boothlock_server.tableqr.dto.TableBulkCreateResponse;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.support.SecureTokenGenerator;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * O2 테이블 일괄 등록·O5 QR 재발급 (명세서 O2·O5) — JWT·STAFF 인증은 황대겸의 BoothJwtProvider·BoothInfoService를 그대로 재사용한다.
 */
@Service
public class TableAdminService {

    private static final int MAX_BULK_COUNT = 300;
    private static final int MAX_LABEL_LENGTH = 6;
    private static final int MAX_RAW_LABEL_LENGTH = 20; // booth_table.label VARCHAR(20)
    private static final Pattern LABEL_PATTERN = Pattern.compile("[A-Z0-9]+");

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final TableRepository tableRepository;

    public TableAdminService(BoothJwtProvider jwtProvider,
                              BoothInfoService boothInfoService,
                              TableRepository tableRepository) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.tableRepository = tableRepository;
    }

    /**
     * O2 테이블 일괄 등록 — count+labelPrefix(순번 생성) 또는 labels(직접 지정) 중 하나로 최대 300건 등록.
     * 라벨은 정규화(하이픈·공백 제거+대문자, 6자 이내, 단독 M 금지) 후 부스 내 중복을 막는다.
     * "A-3"과 "A3"처럼 원본이 달라도 정규화가 같으면 중복으로 본다(DB스키마 §1 booth_table 주석) — 원본 UNIQUE 제약만으론 못 막는다.
     */
    @Transactional
    public TableBulkCreateResponse bulkCreate(String authorization, TableBulkCreateRequest request) {
        BoothEntity staffBooth = authenticatedBooth(authorization);
        List<String> rawLabels = resolveRawLabels(request);

        Set<String> normalizedSoFar = tableRepository.findByBoothId(staffBooth.getId()).stream()
                .map(t -> normalize(t.getLabel()))
                .collect(Collectors.toCollection(HashSet::new));

        List<TableEntity> newTables = new ArrayList<>();
        for (String rawLabel : rawLabels) {
            String normalized = validateAndNormalizeLabel(rawLabel);
            if (!normalizedSoFar.add(normalized)) {
                throw new InvalidRequestException("중복된 테이블 라벨입니다 label=" + rawLabel);
            }
            newTables.add(new TableEntity(staffBooth, rawLabel.trim(), SecureTokenGenerator.generate()));
        }

        List<TableAdminResponse> saved = tableRepository.saveAll(newTables).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return new TableBulkCreateResponse(saved);
    }

    private List<String> resolveRawLabels(TableBulkCreateRequest request) {
        if (request == null) {
            throw new InvalidRequestException("등록할 테이블 정보가 없습니다.");
        }
        boolean hasLabels = request.labels() != null && !request.labels().isEmpty();
        boolean hasCount = request.count() != null;
        if (hasLabels == hasCount) {
            throw new InvalidRequestException("count+labelPrefix 또는 labels 중 하나만 지정해야 합니다.");
        }

        if (hasLabels) {
            if (request.labels().size() > MAX_BULK_COUNT) {
                throw new InvalidRequestException("한 번에 등록 가능한 테이블은 최대 " + MAX_BULK_COUNT + "건입니다.");
            }
            return request.labels();
        }

        if (request.count() < 1 || request.count() > MAX_BULK_COUNT) {
            throw new InvalidRequestException("count는 1~" + MAX_BULK_COUNT + " 사이여야 합니다.");
        }
        if (request.labelPrefix() == null || request.labelPrefix().isBlank()) {
            throw new InvalidRequestException("labelPrefix가 필요합니다.");
        }
        List<String> generated = new ArrayList<>();
        for (int i = 1; i <= request.count(); i++) {
            generated.add(request.labelPrefix().trim() + "-" + i);
        }
        return generated;
    }

    /** 라벨 정규화 — 하이픈·공백 제거 + 대문자 (검증 없음, DB에서 불러온 기존 라벨용) */
    private String normalize(String rawLabel) {
        if (rawLabel == null) {
            return "";
        }
        return rawLabel.replaceAll("[\\p{Z}\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** 신규 입력 라벨 검증 + 정규화 — 영숫자만, 6자 이내, 단독 M 금지 (명세서 O2, DB스키마 §1) */
    private String validateAndNormalizeLabel(String rawLabel) {
        if (rawLabel == null || rawLabel.isBlank()) {
            throw new InvalidRequestException("사용할 수 없는 테이블 라벨입니다.");
        }
        if (rawLabel.trim().length() > MAX_RAW_LABEL_LENGTH) {
            throw new InvalidRequestException("사용할 수 없는 테이블 라벨입니다 label=" + rawLabel);
        }
        String normalized = normalize(rawLabel);
        if (normalized.isEmpty() || normalized.length() > MAX_LABEL_LENGTH
                || "M".equals(normalized) || !LABEL_PATTERN.matcher(normalized).matches()) {
            throw new InvalidRequestException("사용할 수 없는 테이블 라벨입니다 label=" + rawLabel);
        }
        return normalized;
    }

    /** O5 QR 재발급 — 기존 tableToken 즉시 폐기, 활성 세션은 유지. 타 부스 테이블은 404로 존재를 숨긴다 */
    @Transactional
    public TableAdminResponse regenerateToken(String authorization, Long tableId) {
        BoothEntity staffBooth = authenticatedBooth(authorization);

        TableEntity table = tableRepository.findById(tableId)
                .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));
        if (!table.getBooth().getId().equals(staffBooth.getId())) {
            throw new NotFoundException("테이블을 찾을 수 없습니다.");
        }

        table.regenerateToken(SecureTokenGenerator.generate());
        return toResponse(table);
    }

    private BoothEntity authenticatedBooth(String authorization) {
        Jwt jwt = jwtProvider.verify(authorization);
        StaffAccountEntity staff = boothInfoService.authenticate(jwt);
        BoothEntity staffBooth = staff.getBooth();
        if (staffBooth == null) {
            throw new ForbiddenException();
        }
        return staffBooth;
    }

    private TableAdminResponse toResponse(TableEntity table) {
        return new TableAdminResponse(table.getId(), table.getLabel(), qrUrl(table.getId()));
    }

    /** O4 QR 다운로드 엔드포인트 링크 — 관리자 화면이 이 주소로 이미지를 내려받는다 */
    private String qrUrl(Long tableId) {
        return "/api/v1/admin/tables/" + tableId + "/qr";
    }
}
