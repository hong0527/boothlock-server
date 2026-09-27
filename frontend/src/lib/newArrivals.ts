import type { CallSummary, OrderSummary } from '../types/dashboard'

/**
 * 주문현황 폴링 사이에 "새로 들어온 것"을 가려낸다 — 새 주문·직원호출 알림(소리·진동·탭 제목)의 판단부.
 *
 * <p>개수가 아니라 id 집합으로 비교한다. 개수로 보면 한 주문을 승인하는 사이 다른 주문이 들어올 때
 * 3→3으로 같아 보여 알림을 놓친다.
 *
 * <p>첫 조회(prev가 null)는 알리지 않는다 — 화면을 열자마자 이미 쌓여 있던 주문 전부로 울리면
 * 운영자가 "새 주문"이라는 신호를 믿지 않게 된다. 조회가 실패한 주기는 스냅샷을 갱신하지 않으므로
 * (호출부 책임) 끊겼다 붙어도 그 사이 들어온 것은 다음 성공 때 새 것으로 잡힌다.
 */
export type ArrivalSnapshot = {
  pendingOrderIds: ReadonlySet<number>
  callIds: ReadonlySet<number>
}

export type Arrivals = {
  newPendingOrders: number
  newCalls: number
}

export function snapshotOf(pendingOrders: readonly OrderSummary[], calls: readonly CallSummary[]): ArrivalSnapshot {
  return {
    pendingOrderIds: new Set(pendingOrders.map((order) => order.orderId)),
    callIds: new Set(calls.map((call) => call.callId)),
  }
}

export function diffArrivals(prev: ArrivalSnapshot | null, next: ArrivalSnapshot): Arrivals {
  if (prev === null) return { newPendingOrders: 0, newCalls: 0 }
  let newPendingOrders = 0
  for (const id of next.pendingOrderIds) if (!prev.pendingOrderIds.has(id)) newPendingOrders += 1
  let newCalls = 0
  for (const id of next.callIds) if (!prev.callIds.has(id)) newCalls += 1
  return { newPendingOrders, newCalls }
}

/** 탭 제목 — 다른 탭·앱을 보다가도 승인대기가 쌓인 걸 알 수 있게. 0건이면 원래 제목으로 되돌린다 */
export function alertTitle(pendingCount: number, baseTitle: string): string {
  return pendingCount > 0 ? `(${pendingCount}) 승인대기 · 부스락` : baseTitle
}
