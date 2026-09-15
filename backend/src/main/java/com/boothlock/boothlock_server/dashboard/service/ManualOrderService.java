package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.dashboard.dto.ManualOrderRequest;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;
import com.boothlock.boothlock_server.order.service.OrderCreateService;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.service.TableSessionService;

import org.springframework.stereotype.Service;

/**
 * O14 수기 주문 입력 (명세서 O14, 기능 5.3) — tableId 지정 시 그 테이블의 활성 세션에 귀속시키고(없으면 C1과 동일 규칙으로
 * 새로 만듦), 검증·저장은 소비자 주문(C3, OrderCreateService)을 그대로 재사용한다.
 */
@Service
public class ManualOrderService {

    private static final String NO_TABLE_LABEL = "M";

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final TableRepository tableRepository;
    private final TableSessionService tableSessionService;
    private final OrderCreateService orderCreateService;

    public ManualOrderService(BoothJwtProvider jwtProvider, BoothInfoService boothInfoService,
            TableRepository tableRepository, TableSessionService tableSessionService,
            OrderCreateService orderCreateService) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.tableRepository = tableRepository;
        this.tableSessionService = tableSessionService;
        this.orderCreateService = orderCreateService;
    }

    public OrderCreateResponse create(String authorization, ManualOrderRequest request) {
        StaffAccountEntity staff = boothInfoService.authenticate(jwtProvider.verify(authorization));
        BoothEntity booth = staff.getBooth();
        if (booth == null) {
            throw new ForbiddenException();
        }

        if (request == null || request.tableId() == null) {
            return orderCreateService.createManual(
                    booth.getId(), null, NO_TABLE_LABEL, null,
                    request == null ? null : request.items());
        }

        // 본문 파라미터(tableId)도 boothId 스코프를 검사한다 — 타 부스 테이블 지정은 404 (IDOR 방지, 명세서 §7-4)
        TableEntity table = tableRepository.findById(request.tableId())
                .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));
        if (!table.getBooth().getId().equals(booth.getId())) {
            throw new NotFoundException("테이블을 찾을 수 없습니다.");
        }

        // 활성 세션 없으면 C1과 동일 규칙으로 새로 만든다 — 테이블은 이 안에서 OCCUPIED로 전환됨
        TableSessionEntity session = tableSessionService.getOrCreateSession(table.getId());
        String label = orderCreateService.normalizeLabel(table.getLabel());
        return orderCreateService.createManual(booth.getId(), session.getId(), label, table.getLabel(), request.items());
    }
}
