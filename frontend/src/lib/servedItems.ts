import { apiFetch } from './apiFetch'

/**
 * 진행 중 주문의 "나간 메뉴" 체크 — 감튀·치킨·콜라처럼 한 주문이 여러 번에 나눠 나갈 때 뭐가 남았는지 보려고 쓴다.
 *
 * 같은 부스의 여러 기기가 함께 보도록 서버에 저장한다. 다만 DB가 아니라 서버 메모리라, API를 재시작(배포)하면
 * 체크가 모두 사라진다(백엔드 ServedItemService). 주문 상태·정산과는 무관한 운영자 손 체크용 메모다.
 */

/** orderId → 나간 itemId 집합 */
export type ServedByOrder = ReadonlyMap<number, ReadonlySet<number>>

type ServedItemsResponse = { orders: { orderId: number; itemIds: number[] }[] }

/** 부스의 체크 전체 — 주문현황 폴링이 함께 부른다 */
export async function fetchServedItems(): Promise<ServedByOrder> {
  const res = await apiFetch('/api/v1/admin/served-items')
  if (!res.ok) throw new Error(`나간 메뉴 체크를 불러오지 못했어요 (${res.status})`)
  const body: ServedItemsResponse = await res.json()
  return new Map(body.orders.map((o) => [o.orderId, new Set(o.itemIds)]))
}

/** 항목 하나 체크/해제 — 멱등이라 다시 보내도 안전하다 */
export function setItemServed(orderId: number, itemId: number, served: boolean) {
  return apiFetch(`/api/v1/admin/orders/${orderId}/items/${itemId}/served`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ served }),
  })
}

/** 서버 목록 위에 아직 응답을 못 받은 내 체크를 덮어쓴다 — 요청 중에 도착한 폴링 응답이 방금 누른 체크를 되돌리지 않게 */
export function withPendingToggles(
  fromServer: ServedByOrder,
  pending: ReadonlyMap<string, { orderId: number; itemId: number; served: boolean }>,
): ServedByOrder {
  if (pending.size === 0) return fromServer
  const merged = new Map<number, Set<number>>()
  for (const [orderId, ids] of fromServer) merged.set(orderId, new Set(ids))
  for (const { orderId, itemId, served } of pending.values()) {
    const ids = merged.get(orderId) ?? new Set<number>()
    if (served) ids.add(itemId)
    else ids.delete(itemId)
    merged.set(orderId, ids)
  }
  return merged
}
