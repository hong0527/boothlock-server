import type { OrderStatus, OrderSummary } from '../types/dashboard'

/**
 * 주문현황 탭에 실제로 그릴 순서를 정한다.
 *
 * 서버(O10)는 언제나 **접수 시각** 최신순(`createdAt desc, id desc`)으로 준다.
 * 완료·취소한 시각으로는 정렬할 수 없다 — 서버가 그 시각을 응답에 내려주지 않는다.
 *
 * - 승인대기(PENDING_APPROVAL, O28)·진행(RECEIVED): 뒤집어서 **먼저 들어온 주문이 위로.** 만드는 순서대로 처리해야 해서다.
 * - 완료·취소: 서버 순서 그대로(접수 시각 최신 먼저).
 *
 * 완료 탭을 왜 안 뒤집나 — 진행 탭을 오래된 것부터 처리하면 완료되는 순서가 접수 순서와
 * 거의 같아진다. 그래서 접수 시각 최신순이 곧 "최근에 처리한 것부터"가 되어 방금 누른 주문을
 * 맨 위에서 찾는다(되돌리기 동선). 여기까지 뒤집으면 방금 처리한 건이 맨 아래로 간다.
 * 다만 이건 순서대로 처리했을 때의 이야기이고, 중간 건을 건너뛰어 처리하면 어긋날 수 있다.
 *
 * 서버 정렬 자체를 바꾸지 않는 이유 — 같은 쿼리를 정산 CSV 행 순서와 매출 집계가 함께 쓴다.
 *
 * 반환값은 읽기 전용이다. 완료·취소 탭에서는 **state 배열을 그대로 돌려주므로**, 호출부가
 * `sort`·`reverse` 같은 제자리 변형을 하면 state가 몰래 바뀐다. 타입으로 그걸 막는다.
 */
export function orderedForTab(status: OrderStatus, orders: OrderSummary[]): readonly OrderSummary[] {
  // reverse()는 제자리 뒤집기라 복사본에 쓴다 — 원본(state)을 건드리지 않는다
  return status === 'PENDING_APPROVAL' || status === 'RECEIVED' ? [...orders].reverse() : orders
}

/**
 * 진행 탭(승인대기+접수 통합) 표시 순서 — 승인대기를 항상 앞에 둔다. 운영자가 지금 당장 반응해야 할 주문(승인/거절)이
 * 조리 중인 주문 뒤로 밀리면 못 보고 지나친다. 각 묶음 안에서는 orderedForTab과 같이 먼저 들어온 주문이 위로 온다
 */
export function orderedForActive(pending: OrderSummary[], received: OrderSummary[]): readonly OrderSummary[] {
  return [...orderedForTab('PENDING_APPROVAL', pending), ...orderedForTab('RECEIVED', received)]
}

/** 주문 금액 표시 — "12,000원". 운영자가 은행 앱 입금액과 바로 대조하는 숫자라 카드·확인창이 같은 형식을 쓴다 */
export function formatWon(amount: number): string {
  return `${amount.toLocaleString('ko-KR')}원`
}

/** 결제 확인(O11) 확인 문구 — 금액을 보여줘 은행 앱 입금액과 대조하게 한다 */
export function confirmPaymentMessage(order: OrderSummary | undefined): string {
  if (!order) return '입금을 확인 처리할까요?'
  return `${order.orderNo} · ${formatWon(order.totalAmount)}\n입금을 확인 처리할까요?`
}
