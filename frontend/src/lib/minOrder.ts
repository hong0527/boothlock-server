import type { OrderSummary } from '../types/customer'

/**
 * 첫 주문 최소금액(명세서 밖, 파일럿)까지 모자란 금액 — 장바구니가 주문 버튼을 막고 "N원 더" 안내를 띄운다.
 * 서버(OrderCreateService)와 같은 기준: 이 세션에 취소 안 된 메뉴 주문이 있으면 면제, 금액은 메뉴 합계만(자릿세 제외).
 * 0: 막지 않음. 주문내역을 못 불러오면(null) 막지 않는다 — 서버가 다시 검사해 409로 알려 준다
 */
export function minOrderShortfall(
  minOrderAmount: number | undefined,
  menuTotal: number,
  orders: OrderSummary[] | null | undefined,
): number {
  if (!minOrderAmount || minOrderAmount <= 0 || !orders) return 0
  const hasMenuOrder = orders.some((o) => o.status !== 'CANCELED' && o.items.some((i) => i.itemType === 'MENU'))
  return hasMenuOrder ? 0 : Math.max(0, minOrderAmount - menuTotal)
}
