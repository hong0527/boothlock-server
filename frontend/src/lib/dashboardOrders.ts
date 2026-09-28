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

/**
 * 같은 테이블 세션(sessionId)에서 두 번째 이후 주문의 orderId 집합 — "추가 주문" 배지용.
 *
 * 자릿세(부스별 설정, 명세서 밖)가 세션의 첫 메뉴 주문에만 붙어서, 같은 테이블인데 한 카드엔 자릿세가
 * 있고 다른 카드엔 없어 서로 무관한 주문처럼 보일 수 있다 — 그중 나중 주문임을 표시해 헷갈리지 않게 한다.
 *
 * 판정은 지금 화면에 불러온 주문들(주문현황의 진행·완료·취소 전체) 안에서만 이뤄진다. 서버가 완료·취소를
 * 최근 500건까지만 주므로(DashboardQueryService), 세션의 첫 주문이 그 밖으로 밀리면 뒤 주문에 배지가
 * 안 붙는다 — 배지가 없다고 첫 주문이라는 뜻은 아니다.
 *
 * 세는 대상은 "손님이 시킨 주문"뿐이다:
 * - sessionId가 없는 수기 주문 제외.
 * - 자릿세(SEAT_FEE)·기타(EXTRA) 항목만 든 주문 제외. 자릿세는 첫 주문의 *항목*이 아니라 같은 세션의
 *   **별도 주문**이고 createdAt까지 메뉴 주문과 똑같다(OrderWriter.saveSeatFeeOrder) — 세면 자릿세 카드가
 *   "두 번째 주문"으로 잡혀 정작 첫 주문에 배지가 붙는다. 결제 모달로 넣는 기타 항목(추가 자릿세·쿠폰)도 같다.
 * - 취소된 주문 제외. 자릿세 취소는 면제 처리라(OrderRepository) 그걸 첫 주문으로 세면 손님의 진짜 첫
 *   메뉴 주문에 배지가 붙는다. 거절(O28)당한 주문 뒤의 재주문도 마찬가지.
 */
export function additionalOrderIds(orders: readonly OrderSummary[]): ReadonlySet<number> {
  const bySession = new Map<number, OrderSummary[]>()
  for (const order of orders) {
    if (order.sessionId == null) continue
    if (order.status === 'CANCELED') continue
    if (!order.items.some((item) => item.itemType === 'MENU')) continue
    const group = bySession.get(order.sessionId)
    if (group) group.push(order)
    else bySession.set(order.sessionId, [order])
  }
  const result = new Set<number>()
  for (const group of bySession.values()) {
    if (group.length < 2) continue
    // createdAt은 소수점 자릿수가 들쭉날쭉한 ISO 문자열이라 localeCompare가 뒤집는다(ICU가 '.'을 '+'보다
    // 앞에 둔다). 같은 초에 들어온 두 주문의 순서가 갈리므로 파싱해서 비교하고, 동점이면 orderId로 가른다
    const sorted = [...group].sort(
      (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.orderId - b.orderId,
    )
    for (const order of sorted.slice(1)) result.add(order.orderId)
  }
  return result
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
