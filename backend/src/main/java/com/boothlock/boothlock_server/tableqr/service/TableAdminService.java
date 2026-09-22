package com.boothlock.boothlock_server.tableqr.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.domain.StaffAccountEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.booth.service.BoothInfoService;
import com.boothlock.boothlock_server.booth.service.BoothJwtProvider;
import com.boothlock.boothlock_server.global.error.ForbiddenException;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.seat.SeatIdlePolicy;
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
import com.boothlock.boothlock_server.tableqr.repository.TableSequenceRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableSessionRepository;
import com.boothlock.boothlock_server.tableqr.repository.TableUnpaidCountRow;
import com.boothlock.boothlock_server.tableqr.repository.TableUnpaidOrderRepository;
import com.boothlock.boothlock_server.tableqr.support.SecureTokenGenerator;
import com.boothlock.boothlock_server.tableqr.support.TableLabelComparator;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * O2 테이블 일괄 등록·O3 좌석 현황·O5 QR 재발급·O6 퇴실·O22 배치 좌표 (명세서 O2·O3·O5·O6·O22) — JWT·STAFF 인증은 황대겸의 BoothJwtProvider·BoothInfoService를 그대로 재사용한다.
 */
@Service
public class TableAdminService {

    private static final int MAX_BULK_COUNT = 300;
    private static final int MAX_LABEL_LENGTH = 6;
    private static final int MAX_RAW_LABEL_LENGTH = 20; // booth_table.label VARCHAR(20)
    private static final Pattern LABEL_PATTERN = Pattern.compile("[A-Z0-9]+");
    private static final Pattern AUTO_LABEL_PATTERN = Pattern.compile("^T-(\\d+)$");
    /**
     * O22 좌표 상한 — 명세는 "0 이상"만 정했지만 상한이 없으면 INT 범위 밖 값이 저장 단계 500이 되고,
     * 범위 안이라도 2147483647 같은 값은 배치도 화면을 깨뜨린다(§7-20 "화면이 깨지는 것을 서버에서 막는다").
     * 캔버스가 고정 px라 실제 좌표는 수천 이내다. 약도 좌표(mapX·mapY) 상한과 같은 10000으로 둔다
     */
    private static final int MAX_POSITION = 10_000;

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final BoothJwtProvider jwtProvider;
    private final BoothInfoService boothInfoService;
    private final TableRepository tableRepository;
    private final TableSessionRepository tableSessionRepository;
    private final TableUnpaidOrderRepository tableUnpaidOrderRepository;
    private final SeatIdlePolicy seatIdlePolicy;
    private final BoothRepository boothRepository;
    private final TableSequenceRepository tableSequenceRepository;
    private final EntityManager entityManager;

    public TableAdminService(BoothJwtProvider jwtProvider,
                              BoothInfoService boothInfoService,
                              TableRepository tableRepository,
                              TableSessionRepository tableSessionRepository,
                              TableUnpaidOrderRepository tableUnpaidOrderRepository,
                              SeatIdlePolicy seatIdlePolicy,
                              BoothRepository boothRepository,
                              TableSequenceRepository tableSequenceRepository,
                              EntityManager entityManager) {
        this.jwtProvider = jwtProvider;
        this.boothInfoService = boothInfoService;
        this.tableRepository = tableRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.tableUnpaidOrderRepository = tableUnpaidOrderRepository;
        this.seatIdlePolicy = seatIdlePolicy;
        this.boothRepository = boothRepository;
        this.tableSequenceRepository = tableSequenceRepository;
        this.entityManager = entityManager;
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
     * 테이블 1개 자동 채번 등록 — 운영자 화면 "테이블 추가" 버튼 전용. 클라이언트가 번호를 정하지 않고 서버가 채번한다.
     * 번호는 <b>활성 테이블</b>의 자동 라벨 "T-N" 최댓값+1이다 — 테이블을 전부 지우고 다시 만들면 T-1부터 다시 시작한다.
     * 이용 이력이 있어 soft delete된 "T-N" 행이 남아 있으면 새 행을 넣지 않고 그 행을 되살린다(라벨 UNIQUE는 active와 무관하게
     * 걸리고, 과거 세션·주문은 그 행을 가리킨 채 남는다). 되살린 테이블은 토큰을 그대로 쓰므로 예전에 인쇄한 같은 번호 QR이 다시 동작한다.
     *
     * <p>booth.next_table_seq 카운터는 판정에 쓰지 않는다 — soft delete된 번호까지 영구 소진으로 세면 전부 지운 뒤에도
     * 예전 최댓값 다음 번호부터 나온다. 기록만 "마지막으로 낸 번호+1"로 맞춰 둔다.
     * 정규화 라벨이 겹치는 번호는 건너뛴다 — O2로 "T1"이나 "T-1"을 만든 부스에서 "T-1"을 또 만들면 DB 유니크·정규화 규칙(§1)에 걸린다(audit2 H4).
     * 카운터 갱신은 이 컬럼만 UPDATE한다(TableSequenceRepository) — 엔티티 더티 체킹은 전체 컬럼을 써서 동시 O17 변경을 덮는다.
     * 판정 재료(기존 라벨)는 전부 부스 행을 잠근 뒤 잠금 읽기로 얻는다 — {@link #lockBooth} 참조.
     */
    @Transactional
    public TableAdminResponse addSingleTable(String authorization) {
        BoothEntity staffBooth = authenticatedBooth(authorization);
        // 채번은 반드시 booth row를 잠근 트랜잭션 안에서 — 동시에 여러 번 눌러도 번호가 겹치지 않는다 (deleteTable도 같은 락)
        BoothEntity locked = lockBooth(staffBooth.getId());

        List<TableEntity> existing = tableRepository.findByBoothIdForUpdate(locked.getId());   // 삭제(soft delete)된 라벨도 포함 — 유니크 제약이 그렇다
        List<TableEntity> activeTables = existing.stream().filter(TableEntity::isActive).toList();
        Map<String, TableEntity> deletedByLabel = existing.stream()
                .filter(t -> !t.isActive())
                .collect(Collectors.toMap(TableEntity::getLabel, Function.identity()));
        Set<String> normalizedLabels = existing.stream().map(t -> normalize(t.getLabel())).collect(Collectors.toSet());
        Set<String> normalizedActiveLabels = activeTables.stream().map(t -> normalize(t.getLabel())).collect(Collectors.toSet());

        int seq = maxAutoSeq(activeTables) + 1;
        // 되살릴 수 있는 삭제 행("T-N" 그대로)은 건너뛰지 않는다 — 건너뛰면 지운 번호가 다시 안 나온다.
        // 단 활성 라벨과 정규화가 겹치면(O2로 만든 "T3" 옆에 삭제된 "T-3") 되살려도 규칙 위반이라 건너뛴다
        while (normalizedActiveLabels.contains("T" + seq)
                || (!deletedByLabel.containsKey("T-" + seq) && normalizedLabels.contains("T" + seq))) {
            seq++;
        }

        TableEntity deleted = deletedByLabel.get("T-" + seq);
        TableEntity table;
        if (deleted != null) {
            deleted.reactivate();
            table = deleted;
        } else {
            table = tableRepository.save(new TableEntity(locked, "T-" + seq, SecureTokenGenerator.generate()));
        }
        tableSequenceRepository.setNextTableSeq(locked.getId(), seq + 1);
        return toResponse(table);
    }

    /** 부스에 이미 있는 자동 채번 라벨("T-N") 중 가장 큰 N. 없으면 0 */
    private static int maxAutoSeq(List<TableEntity> tables) {
        return tables.stream()
                .map(t -> AUTO_LABEL_PATTERN.matcher(t.getLabel()))
                .filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1)))
                .max()
                .orElse(0);
    }

    /**
     * 테이블 삭제 — 중간 번호가 비면 화면도 지저분해지고 번호 로직도 꼬이므로, 활성 테이블 중 라벨이
     * 가장 큰(마지막) 테이블만 삭제를 허용한다. 사용 중(활성 세션 있음)인 테이블은 항상 막는다.
     * 이용 이력이 전혀 없으면 완전 삭제(QR도 함께 폐기)하고 채번 카운터를 반납해 다음 추가 때 같은
     * 번호가 그대로 되살아나게 한다. 이미 사용된 적 있는 테이블은 과거 세션의 외래키가 깨지므로
     * 기존처럼 soft delete만 하고 번호는 반납하지 않는다.
     *
     * 잠금 순서: 부스 행 → 테이블 행 → 그 테이블의 세션 행 → 부스의 활성 테이블 행. addSingleTable도 부스 행을 먼저 잠그므로
     * "마지막 테이블인지" 판정 사이에 새 테이블이 추가되는 경쟁이 없고, 두 삭제가 겹쳐도 부스 행에서 줄을 서
     * (테이블 행을 먼저 잠그면 서로 상대 테이블 행을 기다리며 교착한다). 테이블 행 락은 C1 세션 생성(TableSessionWriter)과 같은 락이라
     * "사용 중인지" 판정과 삭제가 스캔과 직렬화된다 — 락 없이 판정하면 그 사이 열린 세션이 비활성 테이블에 남거나(soft delete) 외래키 500(hard delete)이 난다.
     * 판정 재료(열린 세션·이용 이력·마지막 테이블 여부)는 전부 잠금 읽기(FOR UPDATE)로 얻는다 — 잠금을 기다린 뒤 일반 조회로 읽으면
     * MySQL REPEATABLE READ에서는 인증 시점 스냅샷이 나와 기다리는 사이 커밋된 세션·테이블을 못 본다(MySQL 8.4 실측).
     * 이미 삭제된 테이블은 404다.
     */
    @Transactional
    public void deleteTable(String authorization, Long tableId) {
        BoothEntity staffBooth = authenticatedBooth(authorization);
        BoothEntity locked = lockBooth(staffBooth.getId());

        TableEntity table = tableRepository.findActiveByIdAndBoothIdForUpdate(tableId, locked.getId())
                .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));

        List<TableSessionEntity> history = tableSessionRepository.findByTableIdForUpdate(tableId);   // 종료된 세션 포함
        if (history.stream().anyMatch(session -> session.getEndedAt() == null && session.getEndedAtKey() == 0)) {
            throw new InvalidStateException("사용 중인 테이블은 삭제할 수 없습니다.");
        }

        if (!isLastActiveTable(table, locked.getId())) {
            throw new InvalidStateException("마지막 번호의 테이블만 삭제할 수 있습니다.");
        }

        if (history.isEmpty()) {
            tableRepository.delete(table);
        } else {
            table.deactivate();   // 과거 세션·주문의 외래키 보호 — 같은 번호를 다시 추가하면 이 행이 되살아난다(addSingleTable)
        }
        if (matchesNextSeq(locked, table.getLabel())) {
            // 번호 반납 — 이 컬럼만 UPDATE (addSingleTable과 같은 이유로 엔티티 더티 체킹을 쓰지 않는다)
            tableSequenceRepository.setNextTableSeq(locked.getId(), locked.getNextTableSeq() - 1);
        }
    }

    /** 활성 테이블 중 라벨이 가장 큰(=마지막) 테이블인지 판단 (O3와 같은 자연 정렬 기준). 부스 행을 잠근 뒤 잠금 읽기로 판정한다 */
    private boolean isLastActiveTable(TableEntity table, Long boothId) {
        return tableRepository.findByBoothIdAndActiveTrueForUpdate(boothId).stream()
                .max(TableLabelComparator.BY_LABEL)
                .map(TableEntity::getId)
                .map(table.getId()::equals)
                .orElse(false);
    }

    /**
     * 부스 행 배타 잠금 + 최신 상태 재적재. 인증(authenticatedBooth)이 같은 영속성 컨텍스트에 이 부스를 이미 올려 두었을 수 있는데,
     * 그 사본이 초기화돼 있으면 잠금 조회(findByIdForUpdate)는 행을 잠글 뿐 사본의 필드(next_table_seq)를 덮어쓰지 않는다 —
     * 옛 카운터로 채번하면 라벨 중복 500이 난다. refresh(PESSIMISTIC_WRITE)는 잠근 채 다시 읽어 사본을 최신 커밋으로 덮어쓴다
     * (MySQL REPEATABLE READ에서도 잠금 읽기라 스냅샷을 무시한다). 반드시 트랜잭션 안에서 부른다
     */
    private BoothEntity lockBooth(Long boothId) {
        BoothEntity locked = boothRepository.findByIdForUpdate(boothId)
                .orElseThrow(() -> new NotFoundException("부스를 찾을 수 없습니다."));
        entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);
        return locked;
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
     * O3 좌석 현황 — 라벨 순으로 부스의 모든(삭제 제외) 테이블 상태를 반환한다.
     * 테이블 수와 무관하게 조회는 고정 횟수다(테이블·열린 세션·미결제 건수 각 1회) — 운영자 화면이 폴링하기 때문이다.
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
        return new TableStatusListResponse(toStatusResponses(tables));
    }

    /**
     * O22 배치 좌표 저장 — 응답은 O3 항목과 정확히 같은 타입이다(명세 O22: 두 곳의 형태가 갈라지면 클라이언트가 두 벌로 파싱한다).
     * 좌표는 표시 전용이라 세션·주문에 영향이 없다. 변경 컬럼만 UPDATE되므로(@DynamicUpdate) 동시 C1·O5가 쓴 값을 덮지 않는다
     */
    @Transactional
    public TableStatusResponse updatePosition(String authorization, Long tableId, TablePositionRequest request) {
        BoothEntity staffBooth = authenticatedBooth(authorization);
        if (request == null) {
            throw new InvalidRequestException("posX와 posY가 필요합니다.");
        }
        int posX = validatePosition("posX", request.posX());
        int posY = validatePosition("posY", request.posY());

        TableEntity table = tableRepository.findActiveByIdAndBoothId(tableId, staffBooth.getId())
                .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));
        table.updatePosition(posX, posY);
        return toStatusResponses(List.of(table)).getFirst();
    }

    /**
     * 좌표 검증 — 숫자(정수·소수)만 받는다. Jackson이 정수는 Integer·Long·BigInteger, 소수는 Double·BigDecimal로 넘긴다.
     * 소수는 반올림(HALF_UP)해 수용한다: 운영자 프론트의 드래그 좌표는 clientX 차이라 반올림 없이 소수로 올 수 있는데(TableGridCard),
     * 표시 전용 값이라 1px 미만 오차는 의미가 없다. 음수·상한 초과·문자열("120")·불리언·배열·null은 400 —
     * 문자열까지 받아주면 클라이언트 버그가 조용히 묻힌다(§7-20)
     */
    private int validatePosition(String field, Object value) {
        if (!(value instanceof Number number)) {
            throw new InvalidRequestException(field + "는 0~" + MAX_POSITION + " 사이의 숫자여야 합니다.");
        }
        // 1e400처럼 double 범위를 넘는 입력은 Infinity로 들어온다 — BigDecimal 변환에서 500이 나지 않게 먼저 거른다
        if ((number instanceof Double || number instanceof Float) && !Double.isFinite(number.doubleValue())) {
            throw new InvalidRequestException(field + "는 0~" + MAX_POSITION + " 사이의 숫자여야 합니다.");
        }
        BigDecimal exact = number instanceof BigDecimal decimal ? decimal
                : number instanceof BigInteger integer ? new BigDecimal(integer)
                : number instanceof Double || number instanceof Float ? BigDecimal.valueOf(number.doubleValue())
                : BigDecimal.valueOf(number.longValue());
        if (exact.signum() < 0) {
            throw new InvalidRequestException(field + "는 0~" + MAX_POSITION + " 사이의 숫자여야 합니다.");
        }
        BigDecimal rounded = exact.setScale(0, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.valueOf(MAX_POSITION)) > 0) {
            throw new InvalidRequestException(field + "는 0~" + MAX_POSITION + " 사이의 숫자여야 합니다.");
        }
        return rounded.intValueExact();
    }

    /**
     * O6 퇴실·초기화("결제 완료" 버튼) — 열린 세션 종료(해당 sessionToken 즉시 410) + status EMPTY. 주문은 건드리지 않는다.
     * 미결제 주문이 있어도 막지 않고 warning으로만 알려준다(명세서 O6 "Should").
     *
     * <p>멱등: 열린 세션이 없어도 200이다. 두 번째 호출은 조건부 UPDATE가 0건으로 끝나고 status는 이미 EMPTY다.
     * 유휴 만료로 세션이 정리된 "정리 필요"(OCCUPIED+session 없음) 테이블도 이 경로로 비운다 — 410이면 영영 비울 수 없다.
     *
     * <p>동시성: 테이블 row를 먼저 잠근다. C1 세션 생성도 같은 row를 먼저 잠그므로(TableSessionWriter)
     * 퇴실·재스캔·퇴실 연타가 이 row에서 줄을 서고, 세션 유니크 제약(table_id, ended_at_key) 충돌이 구조적으로 생기지 않는다.
     * 세션 종료는 조건부 UPDATE라 잠금 대기 중에 커밋된 세션까지 최신 상태로 보고 닫는다.
     * 손님 쪽 활동 기록(touchIfActive)도 조건부 UPDATE라 퇴실 커밋 뒤에는 0건이 되어 410으로 떨어진다.
     *
     * <p>warning 건수는 이 퇴실로 종료한 세션(유휴 포함)의 미결제 수다. 순서가 중요하다 — 세션 행을 잠그고 종료 UPDATE를 한 뒤에 센다.
     * 주문 저장 트랜잭션은 세션 행을 조건부 UPDATE로 잠근 채 주문을 넣으므로, 세션 행 잠금 조회·종료 UPDATE가 그 잠금을 기다리고 나면
     * 끼어든 주문은 이미 커밋돼 있고(집계에 잡힘) 그 뒤 주문은 410으로 막힌다. 집계를 먼저 하면 그 틈의 주문이 경고에서 빠진다(M2).
     * 세션 조회와 집계는 둘 다 잠금 읽기(FOR UPDATE)다 — 일반 조회는 MySQL REPEATABLE READ에서 인증 시점 스냅샷을 읽어
     * 잠금을 기다리는 사이 커밋된 세션·주문을 못 본다(MySQL 8.4 실측: 끼어든 주문이 경고에서 빠짐).
     */
    @Transactional
    public TableCheckoutResponse checkoutTable(String authorization, Long tableId) {
        BoothEntity staffBooth = authenticatedBooth(authorization);

        TableEntity table = tableRepository.findActiveByIdAndBoothIdForUpdate(tableId, staffBooth.getId())
                .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));

        List<Long> endingSessionIds = tableSessionRepository.findOpenByTableIdForUpdate(table.getId()).stream()
                .map(TableSessionEntity::getId)
                .toList();
        tableSessionRepository.endOpenSessions(table.getId(), seatIdlePolicy.now());
        long unpaidOrderCount = endingSessionIds.isEmpty() ? 0
                : tableUnpaidOrderRepository.findUnpaidOrdersOfSessionsForUpdate(endingSessionIds, staffBooth.getId()).size();
        table.vacate();

        String warning = unpaidOrderCount > 0 ? "미결제 주문 " + unpaidOrderCount + "건 있음" : null;
        return new TableCheckoutResponse(unpaidOrderCount > 0, table.getId(), table.getLabel(), table.getStatus(), warning);
    }

    /**
     * O3·O22 공용 조립 — 테이블 목록 크기와 무관하게 세션 1회·미결제 1회 조회로 끝난다.
     * 활성 판정 기준(SeatIdlePolicy.Criteria)은 요청당 한 번만 구해 모든 테이블에 같은 기준을 쓴다.
     * session은 정책상 활성인 세션만 싣고, needsCleanup은 E1·C1과 같은 기준이다:
     * 유휴이고 오늘 영업일 미결제도 없는 세션은 활성으로 보지 않으므로 OCCUPIED+그런 세션 = 정리 필요.
     * unpaidOrderCount는 유휴 여부와 무관하게 열린 세션의 미결제(UnpaidOrderRule: RECEIVED·DONE && UNPAID) 수다 — 퇴실 전 확인용이다
     */
    private List<TableStatusResponse> toStatusResponses(List<TableEntity> tables) {
        List<Long> tableIds = tables.stream().map(TableEntity::getId).toList();
        SeatIdlePolicy.Criteria criteria = seatIdlePolicy.criteria();

        // 테이블당 열린 세션은 DB 유니크 제약으로 최대 1개지만, 제약이 깨진 데이터에서 500이 나지 않게 최근 활동 쪽을 고른다
        Map<Long, TableSessionEntity> openSessionByTable = tableSessionRepository.findOpenByTableIds(tableIds).stream()
                .collect(Collectors.toMap(
                        session -> session.getTable().getId(),
                        Function.identity(),
                        (a, b) -> a.getLastActivityAt().isAfter(b.getLastActivityAt()) ? a : b));
        Map<Long, TableUnpaidCountRow> unpaidByTable = unpaidOrderCounts(tableIds, criteria);

        return tables.stream()
                .map(table -> {
                    TableSessionEntity openSession = openSessionByTable.get(table.getId());
                    // 테이블당 열린 세션은 최대 1개라 테이블 단위 미결제 수가 곧 그 세션의 미결제 수다
                    TableUnpaidCountRow unpaid = unpaidByTable.get(table.getId());
                    boolean active = criteria.isActive(openSession, unpaid != null && unpaid.getUnpaidOrderCountToday() > 0);
                    TableStatusResponse.Session session = active
                            ? new TableStatusResponse.Session(
                                    openSession.getStartedAt().atOffset(KST),
                                    openSession.getLastActivityAt().atOffset(KST),
                                    openSession.getId())
                            : null;
                    boolean needsCleanup = table.getStatus() == TableStatus.OCCUPIED && session == null;
                    return new TableStatusResponse(
                            table.getId(),
                            table.getLabel(),
                            table.getStatus(),
                            needsCleanup,
                            table.getPosX(),
                            table.getPosY(),
                            session,
                            unpaid == null ? 0 : Math.toIntExact(unpaid.getUnpaidOrderCount()));
                })
                .toList();
    }

    private Map<Long, TableUnpaidCountRow> unpaidOrderCounts(List<Long> tableIds, SeatIdlePolicy.Criteria criteria) {
        return tableUnpaidOrderRepository.countUnpaidOrdersOfOpenSessions(tableIds, criteria.businessDate()).stream()
                .collect(Collectors.toMap(TableUnpaidCountRow::getTableId, Function.identity()));
    }

    /**
     * O5 QR 재발급 — 기존 tableToken 즉시 폐기, 활성 세션은 유지. 타 부스·삭제된 테이블은 404로 존재를 숨긴다.
     * 변경 컬럼만 UPDATE되므로(@DynamicUpdate) 동시에 첫 스캔(C1)이 status를 바꿔도 새 토큰이 옛 값으로 되돌아가지 않는다
     */
    @Transactional
    public TableAdminResponse regenerateToken(String authorization, Long tableId) {
        BoothEntity staffBooth = authenticatedBooth(authorization);

        TableEntity table = tableRepository.findActiveByIdAndBoothId(tableId, staffBooth.getId())
                .orElseThrow(() -> new NotFoundException("테이블을 찾을 수 없습니다."));

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
