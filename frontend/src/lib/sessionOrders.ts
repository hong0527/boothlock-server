import type { OrderSummary } from '../types/dashboard'
import type { TableSessionInfo } from '../types/table'

/**
 * 지금 앉은 손님의 주문 고르기 — O10 `OrderSummary.sessionId`와 O3 `session.id`를 맞춘다(둘 다 세션 PK).
 * 예전엔 O3 `session.startedAt` 이후 주문으로 근사했지만(06:00 영업일 경계·시각 비교의 한계) 이제 백엔드가 세션 id를 주므로
 * 시각 비교는 쓰지 않는다. 결제 모달은 아예 O10 `?tableId=&activeSessionOnly=true`로 서버가 골라 준 결과를 쓰고,
 * 이 함수는 테이블-홈 카드처럼 O10을 한 번만 불러 테이블별로 나눠야 하는 곳에서 쓴다.
 * session이 null(빈 테이블·유휴로 정리 필요)이면 현재 손님이 없다고 보고 아무것도 고르지 않는다.
 */
export function isOrderOfSession(
  order: Pick<OrderSummary, 'sessionId'>,
  session: Pick<TableSessionInfo, 'id'> | null | undefined,
): boolean {
  if (!session) return false
  return order.sessionId != null && order.sessionId === session.id
}

export function ordersOfSession<T extends Pick<OrderSummary, 'sessionId'>>(
  orders: T[],
  session: Pick<TableSessionInfo, 'id'> | null | undefined,
): T[] {
  return orders.filter((o) => isOrderOfSession(o, session))
}

/**
 * 미결제 = 서빙 여부와 무관하게 입금이 안 된 주문 — 백엔드 UnpaidOrderRule(RECEIVED·DONE && UNPAID)과 같은 정의.
 * O3 unpaidOrderCount·O6 퇴실 경고·O24 일괄 입금 대상이 전부 이 조건이라, 화면 합계(O24 expectedTotal)도 같아야 409가 나지 않는다.
 * 취소된 미입금(CANCELED+UNPAID)은 받을 돈이 없어 제외.
 */
export function isUnpaid(order: Pick<OrderSummary, 'status' | 'paymentStatus'>): boolean {
  return (order.status === 'RECEIVED' || order.status === 'DONE') && order.paymentStatus === 'UNPAID'
}

/** 화면에 보여준 합계 = O24 expectedTotal. 서버 재계산과 다르면 409 */
export function unpaidTotal(orders: Pick<OrderSummary, 'status' | 'paymentStatus' | 'totalAmount'>[]): number {
  return orders.filter(isUnpaid).reduce((sum, o) => sum + o.totalAmount, 0)
}

/**
 * 승인대기(O28) 요약 — 결제 모달 "승인대기 N건 · X원" 줄과 "결제 완료" 사전 차단에 쓴다.
 * 승인대기는 미결제 정의(isUnpaid)에서 빠져 O24 합계에 안 들어가지만, 손님 결제 안내 화면은 이 금액까지 더한 총액을
 * 이체하라고 보여준다 — 운영자 화면에서 안 보이면 "손님이 더 보냈다"를 설명할 길이 없다. 서버는 승인대기가 남은
 * "결제 완료"(requireSettled)를 409 CHECKOUT_PENDING_APPROVAL로 되돌리고, "테이블 비우기"는 자동 거절한다.
 * paidCount는 승인대기인데 이미 입금 확인된 건(#117 "결제 확인") — 거절되면 '환불필요'로 넘어간다
 */
export function pendingApprovalSummary(
  orders: Pick<OrderSummary, 'status' | 'paymentStatus' | 'totalAmount'>[],
): { count: number; amount: number; paidCount: number } {
  const pending = orders.filter((o) => o.status === 'PENDING_APPROVAL')
  return {
    count: pending.length,
    amount: pending.reduce((sum, o) => sum + o.totalAmount, 0),
    paidCount: pending.filter((o) => o.paymentStatus === 'PAID').length,
  }
}

export type SessionOrderAggregate = {
  orderItems: { menuName: string; qty: number }[]
  orderTotal: number
  firstOrderAt: string | null
  pendingApprovalCount: number
}

/**
 * 테이블-홈 카드 집계 — O10 목록(영업일 전체)에서 지금 앉은 손님(세션) 주문만 골라 항목·합계·첫 주문 시각을 낸다.
 * 취소는 뺀다. 승인대기(O28)는 합계·항목에서 뺀다 — 결제 모달(PaymentModal)과 같은 기준이라야 카드와 모달의 금액이
 * 어긋나지 않는다(운영자가 아직 받아들이지 않은 주문은 확정된 이용 내역이 아니다). 대신 건수를 따로 세어 카드에 표시한다.
 * firstOrderAt은 승인대기도 포함한다 — "손님이 언제부터 앉아 있었나"(장시간 배색 기준)라 승인 여부와 무관하다
 */
export function aggregateSessionOrders(
  session: Pick<TableSessionInfo, 'id'> | null | undefined,
  orders: Pick<OrderSummary, 'sessionId' | 'status' | 'totalAmount' | 'createdAt' | 'items'>[],
): SessionOrderAggregate {
  const items = new Map<string, number>()
  let total = 0
  let firstOrderAt: string | null = null
  let pendingApprovalCount = 0
  for (const order of orders) {
    if (order.status === 'CANCELED') continue
    if (!isOrderOfSession(order, session)) continue
    if (firstOrderAt === null || Date.parse(order.createdAt) < Date.parse(firstOrderAt)) firstOrderAt = order.createdAt
    if (order.status === 'PENDING_APPROVAL') {
      pendingApprovalCount++
      continue
    }
    total += order.totalAmount
    for (const item of order.items) {
      items.set(item.menuName, (items.get(item.menuName) ?? 0) + item.qty)
    }
  }
  return {
    orderItems: Array.from(items, ([menuName, qty]) => ({ menuName, qty })),
    orderTotal: total,
    firstOrderAt,
    pendingApprovalCount,
  }
}
