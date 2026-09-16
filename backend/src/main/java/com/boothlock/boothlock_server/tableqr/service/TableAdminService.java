package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.order.repository.OrderRepository;
import com.boothlock.boothlock_server.tableqr.domain.TableEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableSessionEntity;
import com.boothlock.boothlock_server.tableqr.domain.TableStatus;
import com.boothlock.boothlock_server.tableqr.dto.TableAdminResponse;
import com.boothlock.boothlock_server.tableqr.dto.TableBulkCreateRequest;
import com.boothlock.boothlock_server.tableqr.dto.TableBulkCreateResponse;
import com.boothlock.boothlock_server.tableqr.dto.TableCheckoutResponse;
import com.boothlock.boothlock_server.tableqr.dto.TablePositionRequest;
import com.boothlock.boothlock_server.tableqr.dto.TableStatusListResponse;
import com.boothlock.boothlock_server.tableqr.dto.TableStatusResponse;
import com.boothlock.boothlock_server.tableqr.repository.TableRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.support.SecureTokenGenerator;
import com.boothlock.boothlock_server.tableqr.support.TableLabelComparator;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * O2 테이블 일괄 등록·O3 좌석 현황·O5 QR 재발급 (명세서 O2·O3·O5) — JWT·STAFF 인증은 황대겸의 BoothJwtProvider·BoothInfoService를 그대로 재사용한다.
 */
@Service
public class TableAdminService {

    private static final int MAX_BULK_COUNT = 300;
    private static final int MAX_LABEL_LENGTH = 6;
    private static final int MAX_RAW_LABEL_LENGTH = 20; // booth_table.label VARCHAR(20)
    private static final Pattern LABEL_PATTERN = Pattern.compile("[A-Z0-9]+");
    private static final Pattern AUTO_LABEL_PATTERN = Pattern.compile("^T-(\\d+)$");

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final TableRepository tableRepository;
    private final TableSessionRepository tableSessionRepository;
    private final OrderRepository orderRepository;
    private final BoothRepository boothRepository;

    public TableAdminService(BoothJwtProvider jwtProvider,
                              BoothInfoService boothInfoService,
                              TableRepository tableRepository,
                              TableSessionRepository tableSessionRepository,
                              OrderRepository orderRepository,
                              BoothRepository boothRepository) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.tableRepository = tableRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.orderRepository = orderRepository;
        this.boothRepository = boothRepository;
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

    /**
     * 테이블 1개 자동 채번 등록 — 운영자 화면 "테이블 추가" 버튼 전용. 클라이언트가 번호를 정하지 않고
     * 부스별 영구 카운터(BoothEntity.nextTableSeq)로 서버가 채번한다. 삭제해도 이 카운터는 줄지 않아
     * 번호가 재사용되지 않는다(이미 인쇄된 QR·과거 주문 기록과의 혼동 방지).
     */
    @Transactional
    public TableAdminResponse addSingleTable(String authorization) {
        BoothEntity staffBooth = authenticatedBooth(authorization);
        // 채번은 반드시 booth row를 잠근 트랜잭션 안에서 — 동시에 여러 번 눌러도 번호가 겹치지 않는다
        BoothEntity locked = boothRepository.findByIdForUpdate(staffBooth.getId())
                .orElseThrow(() -> new NotFoundException("부스를 찾을 수 없습니다."));

        String label = "T-" + locked.nextTableSeq();
        TableEntity table = tableRepository.save(new TableEntity(locked, label, SecureTokenGenerator.generate()));
        return toResponse(table);
    }

    /**
     * 테이블 삭제 — 중간 번호가 비면 화면도 지저분해지고 번호 로직도 꼬이므로, 활성 테이블 중 라벨이
     * 가장 큰(마지막) 테이블만 삭제를 허용한다. 사용 중(활성 세션 있음)인 테이블은 항상 막는다.
     * 이용 이력이 전혀 없으면 완전 삭제(QR도 함께 폐기)하고 채번 카운터를 반납해 다음 추가 때 같은
     * 번호가 그대로 되살아나게 한다. 이미 사용된 적 있는 테이블은 과거 세션의 외래키가 깨지므로
     * 기존처럼 soft delete만 하고 번호는 반납하지 않는다.
     *
     * 부스 행 잠금은 "마지막 테이블인지" 판정보다 먼저 건다 — addSingleTable도 같은 락을 거니까,
     * 그 사이에 새 테이블이 추가돼서 이 삭제가 더 이상 "마지막"이 아니게 되는 경쟁을 막는다.
     */
    @Transactional
    public void deleteTable(String authorization, Long tableId) {
        BoothEntity staffBooth = authenticatedBooth(authorization);

        TableEntity table = tableRepository.findById(tableId)
                .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));
        if (!table.getBooth().getId().equals(staffBooth.getId())) {
            throw new NotFoundException("테이블을 찾을 수 없습니다.");
        }
        if (tableSessionRepository.findByTableIdAndEndedAtIsNull(tableId).isPresent()) {
            throw new InvalidStateException("사용 중인 테이블은 삭제할 수 없습니다.");
        }

        BoothEntity locked = boothRepository.findByIdForUpdate(staffBooth.getId())
                .orElseThrow(() -> new NotFoundException("부스를 찾을 수 없습니다."));

        if (!isLastActiveTable(table, staffBooth.getId())) {
            throw new InvalidStateException("마지막 번호의 테이블만 삭제할 수 있습니다.");
        }

        if (tableSessionRepository.existsByTableId(tableId)) {
            table.deactivate();
            return;
        }

        tableRepository.delete(table);
        if (matchesNextSeq(locked, table.getLabel())) {
            locked.releaseLastTableSeq();
        }
    }

    /** 활성 테이블 중 라벨이 가장 큰(=마지막) 테이블인지 판단 (O3와 같은 자연 정렬 기준) */
    private boolean isLastActiveTable(TableEntity table, Long boothId) {
        return tableRepository.findByBoothIdAndActiveTrue(boothId).stream()
                .max(TableLabelComparator.BY_LABEL)
                .map(TableEntity::getId)
                .map(table.getId()::equals)
                .orElse(false);
    }

    /** 자동 채번("T-N") 라벨이면서 그 N이 부스가 마지막으로 낸 번호와 같은지 — 반납 대상인지 판단 */
    private boolean matchesNextSeq(BoothEntity booth, String label) {
        Matcher matcher = AUTO_LABEL_PATTERN.matcher(label);
        return matcher.matches() && Long.parseLong(matcher.group(1)) == booth.getNextTableSeq() - 1;
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

    /**
     * O3 좌석 현황 — 라벨 순으로 부스의 모든 테이블 상태를 반환한다. OCCUPIED인데 활성 세션이 없으면 needsCleanup=true('정리 필요').
     * posX/posY·session·unpaidOrderCount는 v0.5 신설 — 명세와 기존 구현(needsCleanup)의 합집합 (O3 각주 참조)
     */
    @Transactional(readOnly = true)
    public TableStatusListResponse getTableStatuses(String authorization) {
        BoothEntity staffBooth = authenticatedBooth(authorization);

        List<TableEntity> tables = tableRepository.findByBoothIdAndActiveTrue(staffBooth.getId()).stream()
                .sorted(TableLabelComparator.BY_LABEL)
                .toList();
        if (tables.isEmpty()) {
            return new TableStatusListResponse(List.of());
        }

        List<Long> tableIds = tables.stream().map(TableEntity::getId).toList();
        Map<Long, TableSessionEntity> activeSessionByTableId = tableSessionRepository
                .findByTableIdInAndEndedAtIsNull(tableIds).stream()
                .collect(Collectors.toMap(s -> s.getTable().getId(), s -> s));

        List<Long> activeSessionIds = activeSessionByTableId.values().stream()
                .map(TableSessionEntity::getId).toList();
        Map<Long, Long> unpaidCountBySessionId = activeSessionIds.isEmpty() ? Map.of()
                : orderRepository.countUnpaidBySessionIds(activeSessionIds).stream()
                        .collect(Collectors.toMap(OrderRepository.SessionUnpaidCount::getSessionId,
                                OrderRepository.SessionUnpaidCount::getCount));

        List<TableStatusResponse> responses = tables.stream()
                .map(table -> toResponse(table, activeSessionByTableId.get(table.getId()), unpaidCountBySessionId))
                .toList();
        return new TableStatusListResponse(responses);
    }

    /** O22 배치 좌표 저장 — 응답은 O3 항목과 정확히 같은 형태를 쓴다 */
    @Transactional
    public TableStatusResponse updatePosition(String authorization, Long tableId, TablePositionRequest request) {
        BoothEntity staffBooth = authenticatedBooth(authorization);

        TableEntity table = tableRepository.findById(tableId)
                .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));
        if (!table.getBooth().getId().equals(staffBooth.getId())) {
            throw new NotFoundException("테이블을 찾을 수 없습니다.");
        }
        table.updatePosition(request.posX(), request.posY());

        TableSessionEntity activeSession = tableSessionRepository
                .findByTableIdAndEndedAtIsNull(table.getId()).orElse(null);
        Map<Long, Long> unpaidCountBySessionId = activeSession == null ? Map.of()
                : orderRepository.countUnpaidBySessionIds(List.of(activeSession.getId())).stream()
                        .collect(Collectors.toMap(OrderRepository.SessionUnpaidCount::getSessionId,
                                OrderRepository.SessionUnpaidCount::getCount));
        return toResponse(table, activeSession, unpaidCountBySessionId);
    }

    /**
     * O6 퇴실·초기화("결제 완료" 버튼) — 활성 세션을 종료하고 테이블을 빈 자리로 되돌린다.
     * 이미 퇴실 처리됐으면(활성 세션 없음) 410로 응답해 재요청이 안전하게(멱등) 실패하게 한다.
     * 미결제 주문이 있어도 막지 않고 warning으로만 알려준다(명세서 O6 "Should").
     */
    @Transactional
    public TableCheckoutResponse checkoutTable(String authorization, Long tableId) {
        BoothEntity staffBooth = authenticatedBooth(authorization);

        TableEntity table = tableRepository.findById(tableId)
                .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));
        if (!table.getBooth().getId().equals(staffBooth.getId())) {
            throw new NotFoundException("테이블을 찾을 수 없습니다.");
        }

        TableSessionEntity session = tableSessionRepository.findByTableIdAndEndedAtIsNull(tableId)
                .orElseThrow(SessionExpiredException::new);

        long unpaidCount = orderRepository.countBySessionIdAndStatusAndPaymentStatus(
                session.getId(), OrderStatus.RECEIVED, PaymentStatus.UNPAID);

        session.end(LocalDateTime.now(KST));
        table.vacate();

        return new TableCheckoutResponse(unpaidCount > 0);
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

    private TableStatusResponse toResponse(TableEntity table, TableSessionEntity activeSession,
            Map<Long, Long> unpaidCountBySessionId) {
        boolean hasActiveSession = activeSession != null;
        boolean needsCleanup = table.getStatus() == TableStatus.OCCUPIED && !hasActiveSession;
        TableStatusResponse.Session session = !hasActiveSession ? null
                : new TableStatusResponse.Session(
                        activeSession.getStartedAt().atOffset(KST), activeSession.getLastActivityAt().atOffset(KST));
        int unpaidOrderCount = !hasActiveSession ? 0
                : unpaidCountBySessionId.getOrDefault(activeSession.getId(), 0L).intValue();
        return new TableStatusResponse(table.getId(), table.getLabel(), table.getStatus(), needsCleanup,
                table.getPosX(), table.getPosY(), session, unpaidOrderCount);
    }

    private TableAdminResponse toResponse(TableEntity table) {
        return new TableAdminResponse(table.getId(), table.getLabel(), qrUrl(table.getId()));
    }

    /** O4 QR 다운로드 엔드포인트 링크 — 관리자 화면이 이 주소로 이미지를 내려받는다 */
    private String qrUrl(Long tableId) {
        return "/api/v1/admin/tables/" + tableId + "/qr";
    }
}
