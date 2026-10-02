import type { OrderSummary } from '../types/dashboard'

/** 조리 대상으로 확정된 진행 주문의 메뉴 총 수량. 결제 여부·나감 체크와는 무관하다. */
export function kitchenMenuSummary(orders: readonly OrderSummary[]) {
  const quantities = new Map<string, number>()
  for (const order of orders) {
    if (order.status !== 'RECEIVED') continue
    for (const item of order.items) {
      if (item.itemType !== 'MENU' || item.qty <= 0) continue
      quantities.set(item.menuName, (quantities.get(item.menuName) ?? 0) + item.qty)
    }
  }
  return [...quantities].map(([name, qty]) => ({ name, qty }))
    .sort((a, b) => b.qty - a.qty || a.name.localeCompare(b.name, 'ko'))
}
