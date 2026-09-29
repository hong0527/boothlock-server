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
 *
 * <p>suppressLate를 주면(숨겨졌던 탭이 다시 보인 직후의 따라잡기 조회 한 번 — lib/staffAlertWatcher가 정한다)
 * 생긴 지 {@link LATE_ALERT_MS}보다 오래된 것은 세지 않는다. 스냅샷에는 남으므로 다음 조회에서도 다시 세지 않는다.
 * 탭이 뒤에 있는 동안 브라우저가 폴링을 늦추거나 멈춰, 돌아오는 순간 이미 지난 주문·호출을 알게 된 경우다 — 그걸로
 * 주문음·호출음(조회 한 번에 각 1번)이 뒤늦게 울리지 않게 한다. 늦게 안 것은 화면(목록·탭 제목)에만 보이면 된다.
 * 평소 조회·실패 뒤 복구된 조회에는 적용하지 않는다 — 화면을 보고 있거나 다른 탭에 있는 직원에게는 늦더라도 소리가 유일한 신호다.
 * 기준은 서버 시각(응답 Date 헤더)과 생긴 시각(createdAt)이다 — 같은 서버 호스트의 시계라 기기 시계가 틀려도 상관없다.
 * 서버 시각을 모르면(헤더 없음·못 읽음) 가리지 않고 센다 — 새 알림을 실수로 놓치지 않게.
 * 프론트와 API가 다른 출처(CORS 분리 배포)면 Date는 기본 노출 헤더가 아니라 읽히지 않는다(CorsConfig EXPOSED_HEADERS에 없음) —
 * 그때는 이 무음 처리가 꺼지고 전부 알린다
 */
export type ArrivalSnapshot = {
  pendingOrderIds: ReadonlySet<number>
  callIds: ReadonlySet<number>
  /** 조회 응답의 서버 시각(ms) — 없으면 늦게 알게 된 것을 가리지 않는다 */
  serverNow?: number
  /** id별 생긴 시각(ms) — 못 읽은 id는 빠져 있다(가리지 않는다) */
  orderCreatedAt?: ReadonlyMap<number, number>
  callCreatedAt?: ReadonlyMap<number, number>
}

export type Arrivals = {
  newPendingOrders: number
  newCalls: number
}

/** 따라잡기 조회에서 이보다 오래된 것은 소리로 알리지 않는다 — 돌아오기 직전에 생긴 것은 그대로 울린다 */
export const LATE_ALERT_MS = 20_000

function createdAtById<T>(items: readonly T[], idOf: (item: T) => number, createdAtOf: (item: T) => string) {
  const byId = new Map<number, number>()
  for (const item of items) {
    const at = Date.parse(createdAtOf(item))
    if (Number.isFinite(at)) byId.set(idOf(item), at)
  }
  return byId
}

export function snapshotOf(
  pendingOrders: readonly OrderSummary[], calls: readonly CallSummary[], serverNow?: number,
): ArrivalSnapshot {
  return {
    pendingOrderIds: new Set(pendingOrders.map((order) => order.orderId)),
    callIds: new Set(calls.map((call) => call.callId)),
    serverNow,
    orderCreatedAt: createdAtById(pendingOrders, (order) => order.orderId, (order) => order.createdAt),
    callCreatedAt: createdAtById(calls, (call) => call.callId, (call) => call.createdAt),
  }
}

export function diffArrivals(
  prev: ArrivalSnapshot | null, next: ArrivalSnapshot, { suppressLate = false }: { suppressLate?: boolean } = {},
): Arrivals {
  if (prev === null) return { newPendingOrders: 0, newCalls: 0 }
  const { serverNow } = next
  const isLate = (createdAt: ReadonlyMap<number, number> | undefined, id: number) => {
    if (!suppressLate || serverNow === undefined) return false
    const at = createdAt?.get(id)
    return at !== undefined && serverNow - at > LATE_ALERT_MS
  }
  let newPendingOrders = 0
  for (const id of next.pendingOrderIds) {
    if (!prev.pendingOrderIds.has(id) && !isLate(next.orderCreatedAt, id)) newPendingOrders += 1
  }
  let newCalls = 0
  for (const id of next.callIds) if (!prev.callIds.has(id) && !isLate(next.callCreatedAt, id)) newCalls += 1
  return { newPendingOrders, newCalls }
}

/** 탭 제목 — 다른 탭·앱을 보다가도 승인대기가 쌓인 걸 알 수 있게. 0건이면 원래 제목으로 되돌린다 */
export function alertTitle(pendingCount: number, baseTitle: string): string {
  return pendingCount > 0 ? `(${pendingCount}) 승인대기 · 부스락` : baseTitle
}
