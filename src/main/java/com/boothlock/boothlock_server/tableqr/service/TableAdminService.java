package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.dto.TableAdminResponse;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.support.SecureTokenGenerator;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O2 테이블 일괄 등록·O5 QR 재발급 (명세서 O2·O5) — JWT·STAFF 인증은 황대겸의 BoothJwtProvider·BoothInfoService를 그대로 재사용한다.
 */
@Service
public class TableAdminService {

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
