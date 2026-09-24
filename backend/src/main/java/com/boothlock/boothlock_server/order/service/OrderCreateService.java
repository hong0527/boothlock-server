package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.ErrorResponse;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.InvalidStateException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.error.OrderClosedException;
import com.boothlock.boothlock_server.global.error.OrderRateLimitedException;
import com.boothlock.boothlock_server.global.error.SessionExpiredException;
import com.boothlock.boothlock_server.global.error.SoldOutException;
import com.boothlock.boothlock_server.global.error.UnauthorizedException;
import com.boothlock.boothlock_server.order.domain.OrderEntity;
import com.boothlock.boothlock_server.order.domain.OrderItemEntity;
import com.boothlock.boothlock_server.order.domain.PaymentMethod;
import com.boothlock.boothlock_server.order.dto.OrderCreateRequest;
import com.boothlock.boothlock_server.order.dto.OrderCreateResponse;
import com.boothlock.boothlock_server.order.dto.OrderCreationResult;
import com.boothlock.boothlock_server.order.repository.OrderRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * C3 주문 생성 — 검증 6단계 → 서버 가격 재계산 → 채번 → 저장 (명세서 C3).
 * 클래스에 @Transactional을 걸지 않는다: 멱등키 동시 요청의 복구가 트랜잭션 밖에서만 가능하기 때문 (OrderWriter 주석 참조).
 * 1단계 세션 유효성 검사(410)는 컨트롤러 앞단의 TableSessionAuthService가 맡는다 — 여기 도달한 boothId·sessionId는 인증된 값이다 (명세서 C3)
 */
@Service
public class OrderCreateService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    /** JVM 기본 시간대에 기대지 않는다 — java -jar 배포에는 -Duser.timezone이 붙지 않아 UTC 서버에서 9시간 어긋난다 */
    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern LABEL_PATTERN = Pattern.compile("[A-Z0-9]+");
    private static final int MAX_ITEM_KINDS = 20;
    private static final int MAX_QTY = 30;
    private static final int MAX_UNPAID_ORDERS = 8;
    /** 자릿세(명세서 밖, 파일럿 전용) — 1인당 금액. 인원수는 세션이 갖고 있고(파티사이즈), 곱해서 하나의 항목으로 붙인다 */
    private static final int SEAT_FEE_PER_PERSON = 3000;
    private static final int MAX_LABEL_LENGTH = 6;
    private static final int MAX_RAW_LABEL_LENGTH = 20;   // table_label VARCHAR(20) — 원본 스냅샷 저장 한도
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;
    /**
     * O14 수기 주문 멱등키 접두 — 손님 C3 키와 같은 전역 unique 컬럼(idempotency_key)을 나눠 쓰므로 저장 값을 구별해 둔다.
     * 접두만으로 막지는 않는다: 손님이 "m:..." 키를 보내면 C3이 그대로 저장하므로, 재조회 때 수기 주문인지(manual)도 함께 본다
     */
    private static final String MANUAL_KEY_PREFIX = "m:";
    private static final int MAX_NUMBERING_ATTEMPTS = 3;

    private final OrderRepository orderRepository;
    private final BoothRepository boothRepository;
    private final MenuLookup menuLookup;
    private final OrderWriter orderWriter;

    public OrderCreateService(OrderRepository orderRepository,
                              BoothRepository boothRepository,
                              MenuLookup menuLookup,
                              OrderWriter orderWriter) {
        this.orderRepository = orderRepository;
        this.boothRepository = boothRepository;
        this.menuLookup = menuLookup;
        this.orderWriter = orderWriter;
    }

    /** partySize 없는 호출부(자릿세와 무관한 기존 테스트 등) 용 — 자릿세를 안 붙인다. 실제 C3 경로는 아래 6-인자 버전을 쓴다 */
    public OrderCreationResult create(Long boothId, Long sessionId, String tableLabel,
                                      String idempotencyKey, OrderCreateRequest request) {
        return create(boothId, sessionId, tableLabel, idempotencyKey, request, null);
    }

    public OrderCreationResult create(Long boothId, Long sessionId, String tableLabel,
                                      String idempotencyKey, OrderCreateRequest request, Integer partySize) {
        if (boothId == null || sessionId == null) {
            throw new UnauthorizedException("세션 정보가 없습니다");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidRequestException("Idempotency-Key 헤더가 필요합니다");
        }
        // 컬럼이 VARCHAR(64)라 초과분을 걸러야 한다 — 저장 단계에서 터지면 원인 구분이 안 돼 500이 나간다
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidRequestException("Idempotency-Key가 너무 깁니다");
        }
        String label = normalizeLabel(tableLabel);
        validateRequest(request);

        BoothEntity booth = boothRepository.findById(boothId)
                .orElseThrow(() -> new NotFoundException("부스를 찾을 수 없습니다"));

        // 멱등 재요청은 여기서 끝낸다 — 채번보다 먼저여야 번호가 소모되지 않고, isOpen보다 먼저여야
        // 이미 접수된 주문의 재요청이 부스 마감 때문에 409로 뒤집히지 않는다
        OrderEntity replayed = findReplayedOrder(idempotencyKey, boothId, sessionId);
        if (replayed != null) {
            return new OrderCreationResult(toResponse(replayed, booth.getBankAccount(), booth.getDepositorName()), false);
        }

        if (!booth.isOpen()) {
            throw new OrderClosedException();
        }
        if (orderRepository.countBySessionIdAndStatusAndPaymentStatus(
                sessionId, OrderStatus.RECEIVED, PaymentStatus.UNPAID) >= MAX_UNPAID_ORDERS) {
            throw new OrderRateLimitedException();
        }

        Map<Long, MenuLookup.MenuInfo> menus = resolveMenus(boothId, request);
        List<OrderItemEntity> items = new ArrayList<>(request.items().stream()
                .map(item -> {
                    MenuLookup.MenuInfo menu = menus.get(item.menuId());
                    // 이름·단가는 주문 순간 스냅샷 — 이후 메뉴가 바뀌어도 이 주문은 불변 (DB스키마 §3-4)
                    return new OrderItemEntity(menu.menuId(), menu.name(), menu.price(), item.qty());
                })
                .toList());
        int total = totalAmount(request, menus);

        // 자릿세(명세서 밖, 파일럿 전용) — 이 세션의 첫 주문이고 인원수를 알 때만 붙인다. 수기 주문(createManual, O14)은
        // 이 블록을 타지 않는다 — 세션 기반 파티사이즈 개념과 무관
        if (partySize != null && partySize > 0 && !orderRepository.existsBySessionId(sessionId)) {
            items.add(OrderItemEntity.seatFee(SEAT_FEE_PER_PERSON, partySize));
            total += SEAT_FEE_PER_PERSON * partySize;
        }

        OrderWriter.OrderSpec spec = new OrderWriter.OrderSpec(
                boothId, sessionId, label, tableLabel.trim(), idempotencyKey,
                // 컬럼이 timestamp(6)라 마이크로초로 잘라 넣는다 — 리눅스 now()는 나노초까지 나와서, 자르지 않으면
                // 첫 응답(메모리 값)과 멱등 재요청 응답(DB 재조회 값)의 createdAt이 달라진다
                total, items, LocalDateTime.now(KST_ZONE).truncatedTo(ChronoUnit.MICROS), false);
        return saveWithRetry(spec, booth.getBankAccount(), booth.getDepositorName());
    }

    /**
     * O14 수기 주문의 Idempotency-Key 헤더를 저장 값("m:" + 키)으로 바꾼다. 헤더가 없으면 null — 멱등 처리 없이 기존과 똑같이 동작한다.
     * 길이 검사는 C3과 같은 이유(컬럼 VARCHAR(64), 저장 단계에서 터지면 500)로 하되 접두 2자를 뺀 62자까지다.
     * 빈 값은 "없음"으로 보지 않고 400이다 — 조용히 멱등 없이 저장하면 클라이언트는 재시도가 안전하다고 믿은 채 이중 주문을 만든다
     */
    public String toManualIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        if (idempotencyKey.isBlank()) {
            throw new InvalidRequestException("Idempotency-Key가 비어 있습니다");
        }
        if (MANUAL_KEY_PREFIX.length() + idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidRequestException("Idempotency-Key가 너무 깁니다");
        }
        return MANUAL_KEY_PREFIX + idempotencyKey;
    }

    /**
     * O14 멱등 재요청 — 같은 키로 이미 만든 수기 주문이 있으면 그 주문을 C3과 같은 응답 형태로 돌려준다(없으면 null).
     * 호출자(ManualOrderService)는 세션 확보·사전 검증보다 먼저 부른다: 재요청이 퇴실한 테이블에 세션을 새로 열거나
     * 그 사이 품절·마감으로 뒤집혀서는 안 된다(C3이 isOpen보다 먼저 재조회하는 것과 같은 이유).
     */
    public OrderCreateResponse findManualReplay(Long boothId, String manualIdempotencyKey) {
        if (manualIdempotencyKey == null) {
            return null;
        }
        OrderEntity replayed = findReplayedManualOrder(manualIdempotencyKey, boothId);
        if (replayed == null) {
            return null;
        }
        BoothEntity booth = boothRepository.findById(boothId)
                .orElseThrow(() -> new NotFoundException("부스를 찾을 수 없습니다"));
        return toResponse(replayed, booth.getBankAccount(), booth.getDepositorName());
    }

    /**
     * O14 수기 주문 — C3(create)와 검증·메뉴 조회·금액 계산을 그대로 재사용한다(복붙 금지).
     * C3과 다른 점만: rate limit 없음, 멱등키는 선택(헤더가 있을 때만 toManualIdempotencyKey 값, 없으면 null),
     * sessionId·tableLabel은 호출자(ManualOrderService)가 이미 정한 값,
     * label은 tableId 지정 시 그 테이블의 정규화 라벨, 미지정 시 "M" (주문번호 M-{통산}, 명세서 O14).
     * 같은 키 동시 요청은 C3과 같은 방식으로 복구한다 — 진 쪽의 unique 위반을 저장 트랜잭션 밖에서 받아 이긴 주문을 재조회한다(saveWithRetry)
     */
    public OrderCreationResult createManual(Long boothId, Long sessionId, String label, String tableLabel,
                                            String manualIdempotencyKey,
                                            List<OrderCreateRequest.OrderItemRequest> items) {
        OrderCreateRequest request = new OrderCreateRequest(items);
        validateRequest(request);

        BoothEntity booth = boothRepository.findById(boothId)
                .orElseThrow(() -> new NotFoundException("부스를 찾을 수 없습니다"));
        if (!booth.isOpen()) {
            throw new OrderClosedException();
        }

        Map<Long, MenuLookup.MenuInfo> menus = resolveMenus(boothId, request);
        List<OrderItemEntity> orderItems = request.items().stream()
                .map(item -> {
                    MenuLookup.MenuInfo menu = menus.get(item.menuId());
                    return new OrderItemEntity(menu.menuId(), menu.name(), menu.price(), item.qty());
                })
                .toList();

        OrderWriter.OrderSpec spec = new OrderWriter.OrderSpec(
                boothId, sessionId, label, tableLabel, manualIdempotencyKey,
                totalAmount(request, menus), orderItems, LocalDateTime.now(KST_ZONE).truncatedTo(ChronoUnit.MICROS), true);
        // 멱등키가 없으면 재요청 복구 분기는 타지 않는다 — 채번 충돌 재시도만 의미가 있다
        try {
            return saveWithRetry(spec, booth.getBankAccount(), booth.getDepositorName());
        } catch (SessionExpiredException e) {
            // 호출자(O14)가 세션을 정한 뒤 저장 전에 퇴실(O6)이 끼어든 경우 — 운영자에게는 손님용 410이 아니라
            // 409로 "다시 시도" 신호를 준다. 재시도하면 활성 세션이 새로 만들어진다
            throw new InvalidStateException("테이블이 퇴실 처리되어 주문을 붙일 수 없습니다. 다시 시도해주세요.");
        }
    }

    /**
     * 결제 모달 수량 증가(O6 항목 +)용 — 그 메뉴를 지금 더 주문할 수 있는지 C3 5단계와 같은 기준으로 검사한다.
     * 부스 마감이면 409 ORDER_CLOSED, 메뉴가 사라졌으면 400, 숨김·품절이면 409 SOLD_OUT. 감소·취소에는 부르지 않는다.
     */
    public void ensureOrderable(Long boothId, Long menuId) {
        BoothEntity booth = boothRepository.findById(boothId)
                .orElseThrow(() -> new NotFoundException("부스를 찾을 수 없습니다"));
        if (!booth.isOpen()) {
            throw new OrderClosedException();
        }
        resolveMenus(boothId, new OrderCreateRequest(List.of(new OrderCreateRequest.OrderItemRequest(menuId, 1))));
    }

    /**
     * 저장 시 제약 위반은 두 종류다 — 멱등키 충돌이면 기존 주문을 200으로 돌려주고,
     * 채번(uq_orders_seq) 충돌이면 번호를 새로 뽑아 최대 3회까지 재시도한다 (명세서 §2).
     */
    private OrderCreationResult saveWithRetry(OrderWriter.OrderSpec spec, String bankAccount, String depositorName) {
        DataIntegrityViolationException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_NUMBERING_ATTEMPTS; attempt++) {
            try {
                return new OrderCreationResult(toResponse(orderWriter.save(spec), bankAccount, depositorName), true);
            } catch (DataIntegrityViolationException e) {
                lastFailure = e;
                // 멱등키가 없는 수기 주문(O14)은 채번 충돌뿐이라 바로 재시도한다 — null 키로 조회하면
                // IS NULL 조건으로 바뀌어 남의 수기 주문이 잡히거나 세션 비교에서 NPE가 난다
                if (spec.idempotencyKey() == null) {
                    continue;
                }
                // 저장 트랜잭션이 끝난 뒤라 여기서는 재조회가 안전하다.
                // 수기 주문은 세션이 없을 수 있고(테이블 미지정) 재요청이 퇴실 뒤 새 세션으로 올 수 있어 부스·수기 여부만 본다
                OrderEntity winner = spec.manual()
                        ? findReplayedManualOrder(spec.idempotencyKey(), spec.boothId())
                        : findReplayedOrder(spec.idempotencyKey(), spec.boothId(), spec.sessionId());
                if (winner != null) {
                    return new OrderCreationResult(toResponse(winner, bankAccount, depositorName), false);
                }
            }
        }
        throw lastFailure;
    }

    /**
     * 멱등키는 전역 unique(명세서 §6)라 남의 주문이 조회될 수 있다 — 세션·부스가 다르면 응답으로 돌려주지 않는다.
     * 키가 전역이라 새로 만들 수도 없으므로(unique 위반) 400으로 거절한다. 정상 클라이언트는 UUID라 충돌하지 않는다.
     */
    private OrderEntity findReplayedOrder(String idempotencyKey, Long boothId, Long sessionId) {
        OrderEntity found = orderRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (found == null) {
            return null;
        }
        if (!sessionId.equals(found.getSessionId()) || !boothId.equals(found.getBoothId())) {
            // 같은 키로는 영영 주문할 수 없으므로 키를 새로 만들라는 신호를 준다 (무한 400 루프 방지)
            throw new InvalidRequestException("요청 키를 새로 생성해 다시 시도해주세요");
        }
        return found;
    }

    /**
     * O14판 findReplayedOrder — 키가 전역 unique라 남의 부스 주문이나 같은 키의 손님 주문(C3이 "m:..." 키를 그대로 저장한 경우)이
     * 잡힐 수 있다. 그 경우 C3과 똑같이 400으로 거절한다. 세션은 비교하지 않는다 — 테이블 미지정 수기 주문은 세션이 없고,
     * 첫 요청 뒤 퇴실(O6)이 끼어들면 재요청이 여는 세션은 다른 세션이지만 같은 주문을 돌려주는 것이 맞다
     */
    private OrderEntity findReplayedManualOrder(String manualIdempotencyKey, Long boothId) {
        OrderEntity found = orderRepository.findByIdempotencyKey(manualIdempotencyKey).orElse(null);
        if (found == null) {
            return null;
        }
        if (!boothId.equals(found.getBoothId()) || !found.isManual()) {
            throw new InvalidRequestException("요청 키를 새로 생성해 다시 시도해주세요");
        }
        return found;
    }

    /**
     * 라벨 정규화 — 하이픈·공백 제거 + 대문자, 영숫자만, 6자 이내, 단독 M 금지 (명세서 §2·O2, DB스키마 §1).
     * O14(ManualOrderService)가 tableId 지정 시 orderNo 접두(label)를 만드는 데도 그대로 재사용한다 — public.
     */
    public String normalizeLabel(String tableLabel) {
        if (tableLabel == null || tableLabel.isBlank()) {
            throw new InvalidRequestException("테이블 정보가 없습니다");
        }
        // 원본도 컬럼(VARCHAR(20))에 스냅샷으로 저장한다 — 정규화본 6자 검사만으로는 하이픈·공백 범벅 원본이 통과해 저장 단계에서 500이 난다
        if (tableLabel.trim().length() > MAX_RAW_LABEL_LENGTH) {
            throw new InvalidRequestException("사용할 수 없는 테이블 라벨입니다 label=" + tableLabel);
        }
        // \p{Z}까지 지운다 — 자바 \s는 전각 공백(U+3000)·NBSP를 잡지 못해 orderNo에 그대로 남는다
        String label = tableLabel.replaceAll("[\\p{Z}\\s-]", "").toUpperCase(Locale.ROOT);
        if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH
                || "M".equals(label) || !LABEL_PATTERN.matcher(label).matches()) {
            throw new InvalidRequestException("사용할 수 없는 테이블 라벨입니다 label=" + tableLabel);
        }
        return label;
    }

    private void validateRequest(OrderCreateRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new InvalidRequestException("주문 항목이 필요합니다");
        }
        if (request.items().size() > MAX_ITEM_KINDS) {
            throw new InvalidRequestException("한 번에 주문할 수 있는 메뉴는 " + MAX_ITEM_KINDS + "종까지입니다");
        }
        for (OrderCreateRequest.OrderItemRequest item : request.items()) {
            if (item == null || item.menuId() == null || item.qty() == null) {
                throw new InvalidRequestException("메뉴와 수량을 모두 입력해주세요");
            }
            if (item.qty() < 1 || item.qty() > MAX_QTY) {
                throw new InvalidRequestException("수량은 1~" + MAX_QTY + "개까지 가능합니다");
            }
        }
        long distinctMenus = request.items().stream()
                .map(OrderCreateRequest.OrderItemRequest::menuId).distinct().count();
        if (distinctMenus != request.items().size()) {
            // 명세의 "1~20종"을 종류 수로 읽어 같은 메뉴를 여러 줄로 보내는 요청은 거부한다
            throw new InvalidRequestException("같은 메뉴가 중복되었습니다");
        }
    }

    /** 5단계 — 미존재·타 부스는 400, 숨김·품절은 409 SOLD_OUT (부분 주문 없이 전체 실패) */
    private Map<Long, MenuLookup.MenuInfo> resolveMenus(Long boothId, OrderCreateRequest request) {
        List<Long> menuIds = request.items().stream()
                .map(OrderCreateRequest.OrderItemRequest::menuId)
                .toList();
        Map<Long, MenuLookup.MenuInfo> found = new LinkedHashMap<>();
        for (MenuLookup.MenuInfo menu : menuLookup.findByBoothIdAndMenuIds(boothId, menuIds)) {
            found.put(menu.menuId(), menu);
        }
        for (Long menuId : menuIds) {
            if (!found.containsKey(menuId)) {
                throw new InvalidRequestException("존재하지 않는 메뉴입니다 menuId=" + menuId);
            }
        }
        List<ErrorResponse.ErrorDetail> unavailable = menuIds.stream()
                .map(found::get)
                .filter(menu -> !menu.orderable())
                .map(menu -> new ErrorResponse.ErrorDetail(menu.menuId(), menu.name()))
                .toList();
        if (!unavailable.isEmpty()) {
            throw new SoldOutException(unavailable);
        }
        return found;
    }

    /** 6단계 — 금액은 요청이 아니라 조회한 메뉴 가격으로만 계산한다 (위변조 차단) */
    private int totalAmount(OrderCreateRequest request, Map<Long, MenuLookup.MenuInfo> menus) {
        return request.items().stream()
                .mapToInt(item -> menus.get(item.menuId()).price() * item.qty())
                .sum();
    }

    private OrderCreateResponse toResponse(OrderEntity order, String bankAccount, String depositorName) {
        // 멱등 재응답은 결제 모달에서 항목이 취소된 뒤일 수 있다 — 취소 항목을 빼야 items 합과 totalAmount가 맞는다 (대시보드 매퍼와 같은 규칙)
        List<OrderCreateResponse.OrderItemResponse> items = order.getItems().stream()
                .filter(item -> !item.isCanceled())
                .map(item -> new OrderCreateResponse.OrderItemResponse(
                        item.getMenuId(), item.getMenuName(), item.getUnitPrice(), item.getQty(), item.subtotal(), item.getItemType()))
                .toList();
        return new OrderCreateResponse(
                order.getId(),
                order.getOrderNo(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getTotalAmount(),
                items,
                new OrderCreateResponse.PaymentGuide(
                        PaymentMethod.BANK_TRANSFER, bankAccount, depositorName, depositorNameRule(order.getOrderNo())),
                order.getCreatedAt().atOffset(KST));
    }

    private String depositorNameRule(String orderNo) {
        return "입금자명을 '이름+" + orderNo + "'로 입력해주세요 (예: 김철수" + orderNo + ")";
    }
}
