import type { OrderStatus, OrderSummary } from '../types/dashboard'

/**
 * 주문현황 탭에 실제로 그릴 순서를 정한다.
 *
 * 서버(O10)는 언제나 최신순(`createdAt desc, id desc`)으로 준다. 탭마다 필요한 순서가 다르다:
 * - 진행(RECEIVED): **먼저 들어온 주문이 위로.** 만드는 순서대로 처리해야 해서 오래된 것부터 봐야 한다.
 * - 완료·취소: 서버 순서(최신 먼저) 그대로. 이 두 탭은 최신 500건에서 잘리므로,
 *   방금 처리한 주문이 맨 위에 있어야 바로 찾는다.
 *
 * 서버 정렬 자체를 바꾸지 않는 이유 — 같은 쿼리를 정산 CSV 행 순서와 결제 모달이 함께 쓴다.
 * 결제 모달은 "가장 최근 주문의 항목부터 수량을 줄인다"는 동작이 응답 순서에 걸려 있다.
 *
 * 원본 배열은 건드리지 않는다(`reverse()`는 제자리 뒤집기라 복사본에 쓴다).
 */
export function orderedForTab(status: OrderStatus, orders: OrderSummary[]): OrderSummary[] {
  return status === 'RECEIVED' ? [...orders].reverse() : orders
}
