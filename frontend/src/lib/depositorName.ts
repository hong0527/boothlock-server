import type { OrderSummary } from '../types/customer'

/**
 * 결제 안내 화면의 입금자명 안내 — 35개 테이블의 이체를 운영자가 은행 앱에서 구분하려면 입금자명이 규칙대로 와야 한다.
 *
 * <p>규칙은 서버가 정한다(C3·C4 payment.depositorNameRule = "이름+주문번호"). 프론트가 따로 규칙을 만들지 않는다 —
 * 주문번호 앞머리가 테이블 라벨이라(OrderCreateService) 주문번호만 넣어도 어느 테이블인지 드러난다.
 *
 * <p>미결제 주문이 여러 건이면 이 화면은 합계 한 번 이체를 안내하므로, 가장 최근 주문(C4는 최신순)의 번호와 규칙을 쓴다.
 * 같은 테이블 주문번호라 운영자는 테이블 합계로 대조한다(O24 테이블 일괄 입금 확인).
 */
export type DepositorGuide = {
  orderNo: string
  rule: string
  /** 미결제 주문이 여러 건인가 — 합계 한 번 이체 안내를 덧붙인다 */
  multiple: boolean
}

export function depositorGuide(unpaidOrders: readonly OrderSummary[]): DepositorGuide | null {
  const latest = unpaidOrders[0]
  if (!latest) return null
  return { orderNo: latest.orderNo, rule: latest.payment.depositorNameRule, multiple: unpaidOrders.length > 1 }
}
