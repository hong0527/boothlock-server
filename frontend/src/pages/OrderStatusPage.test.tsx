import { Children, isValidElement, type ReactNode } from 'react'
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../lib/apiFetch'
import { cancelOrder, completeOrder, refundDone, restoreOrder } from '../lib/orderActions'
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
  cancelOrder: vi.fn(),
  completeOrder: vi.fn(),
  refundDone: vi.fn(),
  restoreOrder: vi.fn(),
}))

type Props = {
  children?: ReactNode
  onClick?: () => void
  onCancel?: (orderId: number) => void
  onComplete?: (orderId: number) => void
  onRestore?: (orderId: number) => void
  onRefundDone?: (orderId: number) => void
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

async function load() {
  vi.mocked(apiFetch).mockImplementation(async (path) => {
    const status = String(path).includes('status=DONE') ? 'DONE'
      : String(path).includes('status=CANCELED') ? 'CANCELED' : 'RECEIVED'
    const orders = status === 'DONE' ? DONE : status === 'CANCELED' ? CANCELED : RECEIVED
    return new Response(JSON.stringify({ orders, calls: [] }), { status: 200 })
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
  for (const action of [cancelOrder, completeOrder, refundDone, restoreOrder]) {
    vi.mocked(action).mockResolvedValue(new Response(null, { status: 200 }))
  }
})

describe('주문현황 탭 정렬', () => {
  it('진행 탭은 먼저 들어온 주문을 맨 위에 그린다', async () => {
    await load()
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
