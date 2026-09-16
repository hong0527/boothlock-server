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
