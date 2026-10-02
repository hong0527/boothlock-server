import type { ServedByOrder } from './servedItems'

/** 진행 주문 중 아직 나감 체크하지 않은 메뉴 수량. 결제 여부와는 무관하다. */
export function kitchenMenuSummary(orders: unknown, servedByOrder?: ServedByOrder): { menuId: number; name: string; qty: number }[] {
  if (!Array.isArray(orders)) return []
  const quantities = new Map<number, { menuId: number; name: string; qty: number; namedAt: number; orderId: number }>()
  for (const order of orders) {
    if (!order || order.status !== 'RECEIVED' || !Array.isArray(order.items)) continue
    const parsedAt = typeof order.createdAt === 'string' ? Date.parse(order.createdAt) : NaN
    const namedAt = Number.isFinite(parsedAt) ? parsedAt : -Infinity
    const orderId = Number.isSafeInteger(order.orderId) ? order.orderId : 0
    for (const item of order.items) {
      if (!item || item.itemType !== 'MENU'
        || !Number.isSafeInteger(item.menuId) || item.menuId <= 0
        || typeof item.menuName !== 'string' || !item.menuName.trim()
        || !Number.isSafeInteger(item.qty) || item.qty <= 0) continue
      if (servedByOrder?.get(order.orderId)?.has(item.itemId)) continue
      const existing = quantities.get(item.menuId)
      if (!existing) {
        quantities.set(item.menuId, { menuId: item.menuId, name: item.menuName.trim(), qty: item.qty, namedAt, orderId })
      } else {
        existing.qty += item.qty
        // 이름은 가장 최근 주문의 스냅샷을 표시한다. 현재 메뉴 목록은 조회하지 않는다.
        if (namedAt > existing.namedAt || (namedAt === existing.namedAt && orderId > existing.orderId)) {
          existing.name = item.menuName.trim()
          existing.namedAt = namedAt
          existing.orderId = orderId
        }
      }
    }
  }
  return [...quantities.values()].map(({ menuId, name, qty }) => ({ menuId, name, qty }))
    .sort((a, b) => b.qty - a.qty || a.name.localeCompare(b.name, 'ko') || a.menuId - b.menuId)
}
