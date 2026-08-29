package com.boothlock.boothlock_server.order.service;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import com.boothlock.boothlock_server.booth.repository.BoothRepository;
import com.boothlock.boothlock_server.global.domain.OrderStatus;
import com.boothlock.boothlock_server.global.domain.PaymentStatus;
import com.boothlock.boothlock_server.global.error.ErrorResponse;
import com.boothlock.boothlock_server.global.error.InvalidRequestException;
import com.boothlock.boothlock_server.global.error.NotFoundException;
import com.boothlock.boothlock_server.global.error.OrderClosedException;
import com.boothlock.boothlock_server.global.error.OrderRateLimitedException;
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
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * C3 주문 생성 — 검증 6단계 → 서버 가격 재계산 → 채번 → 저장 (명세서 C3).
 * 클래스에 @Transactional을 걸지 않는다: 멱등키 동시 요청의 복구가 트랜잭션 밖에서만 가능하기 때문 (OrderWriter 주석 참조).
 * TODO(6강): 1단계 세션 유효성 검사(410 SESSION_EXPIRED)는 세션 인증 계층에서 처리한다 (명세서 C3)
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
    private static final int MAX_LABEL_LENGTH = 6;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;
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

    public OrderCreationResult create(Long boothId, Long sessionId, String tableLabel,
                                      String idempotencyKey, OrderCreateRequest request) {
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
            return new OrderCreationResult(toResponse(replayed, booth.getBankAccount()), false);
        }

        if (!booth.isOpen()) {
            throw new OrderClosedException();
        }
        if (orderRepository.countBySessionIdAndStatusAndPaymentStatus(
                sessionId, OrderStatus.RECEIVED, PaymentStatus.UNPAID) >= MAX_UNPAID_ORDERS) {
            throw new OrderRateLimitedException();
        }

        Map<Long, MenuLookup.MenuInfo> menus = resolveMenus(boothId, request);
        List<OrderItemEntity> items = request.items().stream()
                .map(item -> {
                    MenuLookup.MenuInfo menu = menus.get(item.menuId());
                    // 이름·단가는 주문 순간 스냅샷 — 이후 메뉴가 바뀌어도 이 주문은 불변 (DB스키마 §3-4)
                    return new OrderItemEntity(menu.menuId(), menu.name(), menu.price(), item.qty());
                })
                .toList();

        OrderWriter.OrderSpec spec = new OrderWriter.OrderSpec(
                boothId, sessionId, label, idempotencyKey,
                totalAmount(request, menus), items, LocalDateTime.now(KST_ZONE));
        return saveWithRetry(spec, booth.getBankAccount());
    }

    /**
     * 저장 시 제약 위반은 두 종류다 — 멱등키 충돌이면 기존 주문을 200으로 돌려주고,
     * 채번(uq_orders_seq) 충돌이면 번호를 새로 뽑아 최대 3회까지 재시도한다 (명세서 §2).
     */
    private OrderCreationResult saveWithRetry(OrderWriter.OrderSpec spec, String bankAccount) {
        DataIntegrityViolationException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_NUMBERING_ATTEMPTS; attempt++) {
            try {
                return new OrderCreationResult(toResponse(orderWriter.save(spec), bankAccount), true);
            } catch (DataIntegrityViolationException e) {
                lastFailure = e;
                // 저장 트랜잭션이 끝난 뒤라 여기서는 재조회가 안전하다
                OrderEntity winner = findReplayedOrder(spec.idempotencyKey(), spec.boothId(), spec.sessionId());
                if (winner != null) {
                    return new OrderCreationResult(toResponse(winner, bankAccount), false);
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

    /** 라벨 정규화 — 하이픈·공백 제거 + 대문자, 영숫자만, 6자 이내, 단독 M 금지 (명세서 §2·O2, DB스키마 §1) */
    private String normalizeLabel(String tableLabel) {
        if (tableLabel == null || tableLabel.isBlank()) {
            throw new InvalidRequestException("테이블 정보가 없습니다");
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

    private OrderCreateResponse toResponse(OrderEntity order, String bankAccount) {
        List<OrderCreateResponse.OrderItemResponse> items = order.getItems().stream()
                .map(item -> new OrderCreateResponse.OrderItemResponse(
                        item.getMenuId(), item.getMenuName(), item.getUnitPrice(), item.getQty(), item.subtotal()))
                .toList();
        return new OrderCreateResponse(
                order.getId(),
                order.getOrderNo(),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getTotalAmount(),
                items,
                new OrderCreateResponse.PaymentGuide(
                        PaymentMethod.BANK_TRANSFER, bankAccount, depositorNameRule(order.getOrderNo())),
                order.getCreatedAt().atOffset(KST));
    }

    private String depositorNameRule(String orderNo) {
        return "입금자명을 '이름+" + orderNo + "'로 입력해주세요 (예: 김철수" + orderNo + ")";
    }
}
