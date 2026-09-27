import type { CallSummary, OrderStatus, OrderSummary } from '../types/dashboard'

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

/** 주문 금액 표시 — "12,000원". 운영자가 은행 앱 입금액과 바로 대조하는 숫자라 카드·확인창이 같은 형식을 쓴다 */
export function formatWon(amount: number): string {
  return `${amount.toLocaleString('ko-KR')}원`
}

export const REJECT_UNPAID_WARNING =
  "손님이 이미 입금했다면 먼저 '결제 확인'을 누른 뒤 거절하세요 — 그래야 환불 대상으로 남습니다. 입금 전이면 그대로 거절."

/**
 * 승인대기 거절 확인 문구.
 *
 * 손님은 승인 전에 먼저 이체하라고 안내받는다. 미결제(UNPAID) 상태로 거절하면 환불필요로 남지 않아,
 * 이미 들어온 돈을 돌려줄 대상에서 빠진다. 그래서 거절 전에 '결제 확인'부터 누르라고 알린다.
 * 같은 테이블의 미확인 결제확인 호출(PAYMENT, "입금했어요")이 있으면 입금됐을 가능성이 높아 맨 앞에서 강조한다.
 */
export function rejectConfirmMessage(order: OrderSummary | undefined, calls: readonly CallSummary[]): string {
  if (order && order.paymentStatus !== 'UNPAID') {
    return '이 주문을 거절할까요? (이미 입금확인된 주문이라 환불필요로 남아요)'
  }
  const paymentCalled =
    !!order?.tableLabel && calls.some((call) => call.reason === 'PAYMENT' && call.tableLabel === order.tableLabel)
  const head = paymentCalled
    ? "⚠️ 이 테이블에서 '입금했어요' 결제확인 호출이 와 있어요! 은행 앱에서 입금 여부를 꼭 확인하세요.\n\n"
    : ''
  return `${head}이 주문을 거절할까요?\n\n${REJECT_UNPAID_WARNING}`
}

/** 결제 확인(O11) 확인 문구 — 금액을 보여줘 은행 앱 입금액과 대조하게 한다 */
export function confirmPaymentMessage(order: OrderSummary | undefined): string {
  if (!order) return '입금을 확인 처리할까요?'
  return `${order.orderNo} · ${formatWon(order.totalAmount)}\n입금을 확인 처리할까요?`
}
