package com.boothlock.boothlock_server.dashboard.service;

import com.boothlock.boothlock_server.dashboard.dto.ManualOrderRequest;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;
import com.boothlock.boothlock_server.order.service.OrderCreateService;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.dto.AuthenticatedSession;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionCreateRequest;
import com.boothlock.boothlock_server.tableqr.dto.TableSessionResponse;
import com.boothlock.boothlock_server.tableqr.service.TableSessionAuthService;
import com.boothlock.boothlock_server.tableqr.service.TableSessionService;

import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * O14 수기 주문 — 운영자 인증 → tableId 부스 소속 확인(404) → 요청·접수 스위치·메뉴 검증 → 그 뒤에만 세션 확보 →
 * C3 저장 경로(OrderCreateService.createManual) (명세서 O14). 검증·가격 재계산은 C3을 그대로 재사용한다.
 * 클래스·메서드에 @Transactional을 걸지 않는다: 안에서 부르는 C3 저장(OrderWriter)과 C1 세션 발급(TableSessionWriter)이
 * 각자 트랜잭션을 끊고 제약 위반을 밖에서 복구하는 구조라, 여기서 감싸면 그 복구 경로가 막힌다.
 */
@Service
public class ManualOrderService {

    private static final String NO_TABLE_LABEL = "M";

    private final BoothStaffAuthenticator staffAuthenticator;
    private final BoothTableLookup tableLookup;
    private final ManualOrderPreflight preflight;
    private final OrderCreateService orderCreateService;
    private final TableSessionService tableSessionService;
    private final TableSessionAuthService tableSessionAuthService;

    public ManualOrderService(BoothStaffAuthenticator staffAuthenticator, BoothTableLookup tableLookup,
            ManualOrderPreflight preflight, OrderCreateService orderCreateService,
            TableSessionService tableSessionService, TableSessionAuthService tableSessionAuthService) {
        this.staffAuthenticator = staffAuthenticator;
        this.tableLookup = tableLookup;
        this.preflight = preflight;
        this.orderCreateService = orderCreateService;
        this.tableSessionService = tableSessionService;
        this.tableSessionAuthService = tableSessionAuthService;
    }

    public OrderCreateResponse create(String authorization, ManualOrderRequest request) {
        Long boothId = staffAuthenticator.authenticateBoothId(authorization);

        if (request == null || request.tableId() == null) {
            // 테이블 미지정 — 세션이 없어 부수효과가 없으니 C3 검증을 저장 경로에 그대로 맡긴다
            return orderCreateService.createManual(
                    boothId, null, NO_TABLE_LABEL, null, request == null ? null : request.items());
        }

        // 소속 확인은 세션·주문보다 먼저 — 남의 부스·삭제된 테이블이면 어떤 부수효과도 남기지 않고 404
        TableEntity table = tableLookup.requireTableOfBooth(request.tableId(), boothId);
        // 거절될 주문이 빈 테이블을 사용중으로 바꾸지 않게, 세션을 잡기 전에 C3과 같은 검증을 먼저 통과시킨다 (감사 M4)
        preflight.check(boothId, request);

        String label = orderCreateService.normalizeLabel(table.getLabel());
        Supplier<Long> sessionResolver = activeSessionOf(table, boothId);
        try {
            return orderCreateService.createManual(
                    boothId, sessionResolver.get(), label, table.getLabel(), request.items());
        } catch (SessionExpiredException e) {
            // 세션을 잡은 직후 저장 전에 퇴실(O6)이 커밋된 경우 — 운영자 화면에 손님용 410(QR 재스캔)을 주지 않는다.
            // 자동으로 새 세션을 만들어 재시도하지 않는 이유: 방금 퇴실한 테이블을 운영자 의도 확인 없이 다시 사용중으로 바꾸게 된다
            throw new InvalidStateException("테이블이 방금 퇴실 처리됐습니다. 테이블 상태를 확인한 뒤 다시 시도하세요.");
        }
    }

    /**
     * 테이블의 활성 세션 id — C1(TableSessionService.createOrRestore)을 그 테이블의 QR 토큰으로 그대로 태운다.
     * 활성 세션이 있으면 복원, 없으면 생성 + 테이블 OCCUPIED. 손님 QR 스캔과 동시에 와도 C1의 유니크 제약 복구가 한 세션으로 모으고,
     * 테이블 파트가 C1에 넣는 유휴 판정·행 잠금도 자연히 따라온다 (명세서 O14 "C1과 동일 규칙").
     * C1 응답에는 세션 id가 없어 발급된 토큰을 인증 계층에 통과시켜 얻는다 — 그 사이 퇴실됐으면 여기서 410이 난다.
     */
    private Supplier<Long> activeSessionOf(TableEntity table, Long boothId) {
        return () -> {
            TableSessionResponse issued = tableSessionService.createOrRestore(
                    new TableSessionCreateRequest(table.getTableToken()));
            AuthenticatedSession session = tableSessionAuthService.authenticate(issued.sessionToken());
            // 위에서 부스 소속을 확인한 테이블의 토큰이라 어긋날 수 없다 — 어긋나면 남의 세션에 주문이 붙으므로 저장 전에 끊는다
            if (!boothId.equals(session.boothId())) {
                throw new NotFoundException("테이블을 찾을 수 없습니다.");
            }
            return session.sessionId();
        };
    }
}
