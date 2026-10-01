import type { OrderSummary } from '../types/customer'

/**
 * 최소주문금액(명세서 밖, 파일럿)까지 모자란 금액 — 장바구니가 주문 버튼을 막고 "N원 더" 안내를 띄운다.
 * 서버(OrderCreateService)와 같은 기준: 일행의 살아 있는 메뉴 합계 + 이번 장바구니가 최소금액 이상이어야 한다(자릿세 제외).
 * 일행 합계 = 이 세션의 취소 안 된 메뉴 항목 + inheritedMenuAmount(유휴 인계로 이어진 앞 세션 몫, C1 응답).
 * 0: 막지 않음. 주문내역을 못 불러오면(null) 막지 않는다 — 서버가 다시 검사해 409로 알려 준다
 */
export function minOrderShortfall(
  minOrderAmount: number | undefined,
  menuTotal: number,
  orders: OrderSummary[] | null | undefined,
  inheritedMenuAmount = 0,
): number {
  if (!minOrderAmount || minOrderAmount <= 0 || !orders) return 0
  return Math.max(0, minOrderAmount - inheritedMenuAmount - activeMenuAmount(orders) - menuTotal)
}

/** 이 세션의 살아 있는 메뉴 합계 — 취소된 주문·자릿세·기타 항목은 뺀다(취소된 항목은 C4 응답에 아예 없다) */
function activeMenuAmount(orders: OrderSummary[]): number {
  return orders
    .filter((o) => o.status !== 'CANCELED')
    .flatMap((o) => o.items)
    .filter((i) => i.itemType === 'MENU')
    .reduce((sum, i) => sum + i.unitPrice * i.qty, 0)
}
