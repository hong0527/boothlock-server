import { Children, isValidElement, type ReactNode } from 'react'
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../lib/apiFetch'
import { approveOrder, cancelOrder, completeOrder, confirmOrderPayment, refundDone, restoreOrder } from '../lib/orderActions'
import { playBeep, vibrate } from '../lib/staffAlert'
import OrderStatusPage from './OrderStatusPage'
import type { OrderStatus, OrderSummary } from '../types/dashboard'

// DOM 없이 페이지를 함수로 굴리는 최소 harness (AccountPage.test.tsx와 같은 방식).
// useState 목이 setter를 즉시 반영하므로, setState 뒤 render()를 다시 부르면 바뀐 값으로 그려진다.
const hooks = vi.hoisted(() => ({ values: [] as unknown[], cursor: 0, mounted: false, effects: [] as (() => void)[] }))
vi.mock('react', async () => ({
  ...await vi.importActual<typeof import('react')>('react'),
  useState: (initial: unknown) => {
    const index = hooks.cursor++
    if (!(index in hooks.values)) hooks.values[index] = typeof initial === 'function' ? (initial as () => unknown)() : initial
    return [hooks.values[index], (value: unknown) => {
      hooks.values[index] = typeof value === 'function' ? (value as (p: unknown) => unknown)(hooks.values[index]) : value
    }]
  },
  useEffect: (effect: () => void) => { if (!hooks.mounted) hooks.effects.push(effect) },
  // 의존성 배열을 일부러 무시하고 매번 다시 계산한다 — deps 누락은 아래 [정렬] 테스트가 아니라
  // 렌더 결과로 잡는 게 아니라서, 여기서는 "현재 상태에 맞는 값이 나오는가"만 본다
  useMemo: (factory: () => unknown) => factory(),
  useCallback: (fn: unknown) => fn,
  // 폴링 중복 방지 가드(pollGuard)가 쓴다 — 렌더 사이에 값이 유지돼야 해서 상태 칸을 하나 빌린다
  useRef: (initial: unknown) => {
    const index = hooks.cursor++
    if (!(index in hooks.values)) hooks.values[index] = { current: initial }
    return hooks.values[index]
  },
}))
vi.mock('../lib/apiFetch', () => ({ apiFetch: vi.fn() }))
vi.mock('../lib/orderActions', () => ({
  ackCall: vi.fn(),
  approveOrder: vi.fn(),
  cancelOrder: vi.fn(),
  completeOrder: vi.fn(),
  confirmOrderPayment: vi.fn(),
  refundDone: vi.fn(),
  restoreOrder: vi.fn(),
}))
// 소리·진동은 기기 API라 여기서는 "울리려 했는가"만 본다
vi.mock('../lib/staffAlert', () => ({
  acquireWakeLock: vi.fn(),
  isAlertPreferred: () => false,
  playBeep: vi.fn(),
  releaseWakeLock: vi.fn(),
  setAlertPreferred: vi.fn(),
  unlockAudio: vi.fn(),
  vibrate: vi.fn(),
}))

type Props = {
  children?: ReactNode
  onClick?: () => void
  onApprove?: (orderId: number) => void
  onReject?: (orderId: number) => void
  onCancel?: (orderId: number) => void
  onComplete?: (orderId: number) => void
  onRestore?: (orderId: number) => void
  onRefundDone?: (orderId: number) => void
  onConfirmPayment?: (orderId: number) => void
  order?: OrderSummary
}

function render() {
  hooks.cursor = 0
  const tree = OrderStatusPage()
  hooks.mounted = true
  hooks.effects.splice(0).forEach(effect => effect())
  return tree
}
function collect(node: ReactNode, predicate: (props: Props) => boolean, out: Props[] = []): Props[] {
  for (const child of Children.toArray(node)) {
    if (!isValidElement<Props>(child)) continue
    if (predicate(child.props)) out.push(child.props)
    collect(child.props.children, predicate, out)
  }
  return out
}
/** 화면에 그려진 주문 카드의 주문번호를 위에서부터 */
function renderedOrderNos(): string[] {
  return collect(render(), p => !!p.order).map(p => p.order!.orderNo)
}
function cards() { return collect(render(), p => !!p.order) }
/** 탭 버튼을 누른다 — 라벨로 찾는다 */
function clickTab(label: string) {
  const tab = collect(render(), p => {
    const kids = Children.toArray(p.children)
    return !!p.onClick && kids.some(k => typeof k === 'string' && k === label)
  })[0]
  tab!.onClick!()
}

function order(orderNo: string, createdAt: string, status: OrderStatus): OrderSummary {
  return {
    orderId: Number(orderNo.replace(/\D/g, '')),
    orderNo, status,
    paymentStatus: 'UNPAID', paymentMethod: null,
    totalAmount: 1000, items: [], createdAt,
    tableLabel: 'A-1', manual: false, sessionId: 1,
  }
}

// 서버(O10)가 주는 순서: 접수 시각 최신 먼저
const PENDING_APPROVAL = [
  order('P-2', '2026-09-21T18:50:00', 'PENDING_APPROVAL'),
  order('P-1', '2026-09-21T18:40:00', 'PENDING_APPROVAL'),
]
const RECEIVED = [
  order('R-3', '2026-09-21T18:30:00', 'RECEIVED'),
  order('R-2', '2026-09-21T18:20:00', 'RECEIVED'),
  order('R-1', '2026-09-21T18:10:00', 'RECEIVED'),
]
const DONE = [
  order('D-2', '2026-09-21T17:20:00', 'DONE'),
  order('D-1', '2026-09-21T17:10:00', 'DONE'),
]
const CANCELED = [
  order('C-1', '2026-09-21T16:00:00', 'CANCELED'),
  { ...order('C-2', '2026-09-21T15:00:00', 'CANCELED'), paymentStatus: 'REFUND_NEEDED' as const },
]

let confirmAnswer = true
let confirmMessages: string[]

function ordersForStatus(status: string) {
  if (status === 'PENDING_APPROVAL') return PENDING_APPROVAL
  if (status === 'DONE') return DONE
  if (status === 'CANCELED') return CANCELED
  return RECEIVED
}

async function load() {
  vi.mocked(apiFetch).mockImplementation(async (path) => {
    const match = String(path).match(/status=([A-Z_]+)/)
    return new Response(JSON.stringify({ orders: ordersForStatus(match?.[1] ?? ''), calls: [] }), { status: 200 })
  })
  render()
  await vi.waitFor(() => expect(cards().length).toBeGreaterThan(0))
}

// node 환경에는 window도 localStorage도 없다 — 페이지가 실제로 쓰는 것만 세운다.
// localStorage는 getStaff()가 safeStorage를 거쳐 읽으므로 필요하다.
const store = new Map<string, string>()
const storage = {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => { store.set(k, v) },
  removeItem: (k: string) => { store.delete(k) },
  clear: () => { store.clear() },
}
vi.stubGlobal('localStorage', storage)
vi.stubGlobal('window', {
  confirm: (message: string) => { confirmMessages.push(message); return confirmAnswer },
  localStorage: storage,
})
afterAll(() => { vi.unstubAllGlobals() })

beforeEach(() => {
  hooks.values = []
  hooks.cursor = 0
  hooks.mounted = false
  hooks.effects = []
  confirmAnswer = true
  confirmMessages = []
  store.clear()
  vi.clearAllMocks()
  // 액션 목은 기본으로 성공을 돌려준다 — 안 주면 runOrderAction이 undefined.ok를 읽고 터진다
  for (const action of [approveOrder, cancelOrder, completeOrder, confirmOrderPayment, refundDone, restoreOrder]) {
    vi.mocked(action).mockResolvedValue(new Response(null, { status: 200 }))
  }
})

describe('주문현황 탭 정렬', () => {
  it('기본 진입 탭은 승인대기이고, 먼저 들어온 주문을 맨 위에 그린다 (O28)', async () => {
    await load()
    expect(renderedOrderNos()).toEqual(['P-1', 'P-2'])
  })

  it('진행 탭도 먼저 들어온 주문을 맨 위에 그린다', async () => {
    await load()
    clickTab('진행')
    expect(renderedOrderNos()).toEqual(['R-1', 'R-2', 'R-3'])
  })

  it('완료 탭으로 옮기면 서버 순서(접수 최신 먼저) 그대로 그린다', async () => {
    await load()
    clickTab('완료')
    expect(renderedOrderNos()).toEqual(['D-2', 'D-1'])
  })

  it('취소 탭도 서버 순서 그대로다', async () => {
    await load()
    clickTab('취소')
    expect(renderedOrderNos()).toEqual(['C-1', 'C-2'])
  })

  it('완료 탭에 갔다가 진행 탭으로 돌아와도 진행 탭 정렬이 유지된다', async () => {
    await load()
    clickTab('완료')
    clickTab('진행')
    expect(renderedOrderNos()).toEqual(['R-1', 'R-2', 'R-3'])
  })

  it('폴링으로 목록이 바뀌면 바뀐 목록을 다시 정렬해 그린다', async () => {
    await load()
    clickTab('진행')
    const added = [order('R-4', '2026-09-21T18:40:00', 'RECEIVED'), ...RECEIVED]
    vi.mocked(apiFetch).mockImplementation(async (path) => {
      const orders = String(path).includes('status=RECEIVED') ? added : []
      return new Response(JSON.stringify({ orders, calls: [] }), { status: 200 })
    })
    hooks.effects.splice(0)
    hooks.mounted = false
    render()
    await vi.waitFor(() => expect(renderedOrderNos()).toEqual(['R-1', 'R-2', 'R-3', 'R-4']))
  })
})

describe('주문 취소 확인 단계', () => {
  it('취소를 누르면 먼저 확인을 묻는다', async () => {
    await load()
    await cards()[0].onCancel!(1)
    expect(confirmMessages).toEqual(['이 주문을 취소할까요?'])
  })

  it('확인에서 아니오를 누르면 취소 요청을 보내지 않는다', async () => {
    await load()
    confirmAnswer = false
    cards()[0].onCancel!(1)
    expect(vi.mocked(cancelOrder)).not.toHaveBeenCalled()
  })

  it('확인하면 취소 요청을 보낸다', async () => {
    await load()
    await cards()[0].onCancel!(1)
    expect(vi.mocked(cancelOrder)).toHaveBeenCalledWith(1)
  })

  it('완료 버튼에는 확인을 붙이지 않는다', async () => {
    await load()
    await cards()[0].onComplete!(1)
    expect(confirmMessages).toEqual([])
    expect(vi.mocked(completeOrder)).toHaveBeenCalledWith(1)
  })

  it('되돌리기는 확인을 묻는다', async () => {
    await load()
    clickTab('완료')
    await cards()[0].onRestore!(2)
    expect(confirmMessages).toEqual(['이 주문을 진행 상태로 복구할까요?'])
    expect(vi.mocked(restoreOrder)).toHaveBeenCalledWith(2)
  })
})

describe('주문 승인·거절 (O28)', () => {
  it('승인에는 확인을 묻지 않는다', async () => {
    await load()
    await cards()[0].onApprove!(1)
    expect(confirmMessages).toEqual([])
    expect(vi.mocked(approveOrder)).toHaveBeenCalledWith(1)
  })

  it('거절을 누르면 먼저 확인을 묻는다', async () => {
    await load()
    await cards()[0].onReject!(1)
    expect(confirmMessages).toHaveLength(1)
    expect(confirmMessages[0]).toContain('이 주문을 거절할까요?')
    // 승인 전 입금한 돈이 환불 대상에서 빠지지 않게 — 거절 전에 '결제 확인'부터 누르라고 알린다
    expect(confirmMessages[0]).toContain("먼저 '결제 확인'을 누른 뒤 거절하세요")
  })

  it('거절 확인에서 아니오를 누르면 요청을 보내지 않는다', async () => {
    await load()
    confirmAnswer = false
    cards()[0].onReject!(1)
    expect(vi.mocked(cancelOrder)).not.toHaveBeenCalled()
  })

  it('거절을 확인하면 사유를 "주문 거절"로 취소 요청을 보낸다 — 별도 API 없이 O13을 재사용한다', async () => {
    await load()
    await cards()[0].onReject!(1)
    expect(vi.mocked(cancelOrder)).toHaveBeenCalledWith(1, '주문 거절')
  })
})

describe('환불 완료', () => {
  // 버튼을 실제로 그릴지는 카드가 결제 상태를 보고 정한다 (OrderCard.test.tsx)
  it('ADMIN이면 카드에 환불 처리 수단을 넘긴다', async () => {
    localStorage.setItem('boothlock_staff', JSON.stringify({ role: 'ADMIN', boothId: 1, boothName: '테스트' }))
    await load()
    clickTab('취소')
    expect(cards()[1].onRefundDone).toBeTypeOf('function')
  })

  it('STAFF에게는 붙지 않는다 — 눌러도 백엔드가 403이다', async () => {
    localStorage.setItem('boothlock_staff', JSON.stringify({ role: 'STAFF', boothId: 1, boothName: '테스트' }))
    await load()
    clickTab('취소')
    expect(cards()[1].onRefundDone).toBeUndefined()
  })

  it('확인을 거쳐야 요청이 나간다', async () => {
    localStorage.setItem('boothlock_staff', JSON.stringify({ role: 'ADMIN', boothId: 1, boothName: '테스트' }))
    await load()
    clickTab('취소')
    confirmAnswer = false
    await cards()[1].onRefundDone!(2)
    expect(vi.mocked(refundDone)).not.toHaveBeenCalled()
    confirmAnswer = true
    await cards()[1].onRefundDone!(2)
    expect(vi.mocked(refundDone)).toHaveBeenCalledWith(2)
  })
})

/** 다음 폴링을 흉내낸다 — 마운트 효과를 다시 돌려 refetchAll을 한 번 더 부른다(상태·ref는 유지) */
async function pollWith(pending: OrderSummary[], calls: { callId: number; tableLabel: string; reason: string; createdAt: string }[]) {
  vi.mocked(apiFetch).mockImplementation(async (path) => {
    const orders = String(path).includes('status=PENDING_APPROVAL') ? pending : []
    return new Response(JSON.stringify({ orders, calls }), { status: 200 })
  })
  hooks.effects.splice(0)
  hooks.mounted = false
  render()
  // 네 탭 조회가 모두 나간 뒤, 응답 파싱(json)·상태 반영까지 끝나도록 한 틱 더 기다린다
  await vi.waitFor(() => expect(vi.mocked(apiFetch)).toHaveBeenCalledTimes(4))
  await new Promise((resolve) => setTimeout(resolve, 10))
}

describe('새 주문·호출 알림', () => {
  it('첫 조회는 이미 쌓여 있던 승인대기로 울리지 않는다', async () => {
    await load()
    expect(vi.mocked(playBeep)).not.toHaveBeenCalled()
    expect(vi.mocked(vibrate)).not.toHaveBeenCalled()
  })

  it('다음 폴링에 새 승인대기 주문이 생기면 소리·진동으로 알린다', async () => {
    await load()
    vi.mocked(apiFetch).mockClear()
    await pollWith([order('P-3', '2026-09-21T19:00:00', 'PENDING_APPROVAL'), ...PENDING_APPROVAL], [])
    expect(vi.mocked(playBeep)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(vibrate)).toHaveBeenCalledTimes(1)
  })

  it('새 직원호출도 알린다', async () => {
    await load()
    vi.mocked(apiFetch).mockClear()
    await pollWith(PENDING_APPROVAL, [{ callId: 9, tableLabel: 'A-1', reason: 'HELP', createdAt: '2026-09-21T19:00:00' }])
    await vi.waitFor(() => expect(vi.mocked(playBeep)).toHaveBeenCalledTimes(1))
  })

  it('목록이 그대로면 울리지 않는다', async () => {
    await load()
    vi.mocked(apiFetch).mockClear()
    await pollWith(PENDING_APPROVAL, [])
    expect(vi.mocked(playBeep)).not.toHaveBeenCalled()
  })
})

describe('결제 확인 (O11)', () => {
  it('금액을 보여주는 확인을 거쳐 요청을 보낸다', async () => {
    await load()
    clickTab('진행')
    await cards()[0].onConfirmPayment!(1)
    expect(confirmMessages[0]).toContain('1,000원')
    expect(vi.mocked(confirmOrderPayment)).toHaveBeenCalledWith(1)
  })

  it('확인에서 아니오면 요청하지 않는다', async () => {
    await load()
    confirmAnswer = false
    await cards()[0].onConfirmPayment!(1)
    expect(vi.mocked(confirmOrderPayment)).not.toHaveBeenCalled()
  })
})

describe('거절 경고 강조', () => {
  it('같은 테이블에 미확인 결제확인 호출이 있으면 경고를 강조한다', async () => {
    await load()
    vi.mocked(apiFetch).mockClear()
    await pollWith(PENDING_APPROVAL, [{ callId: 5, tableLabel: 'A-1', reason: 'PAYMENT', createdAt: '2026-09-21T19:00:00' }])
    await cards()[0].onReject!(1)
    expect(confirmMessages[0].startsWith('⚠️')).toBe(true)
  })
})
