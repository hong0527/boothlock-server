import { Children, isValidElement, type ReactNode } from 'react'
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../lib/apiFetch'
import { approveOrder, cancelOrder, completeOrder, confirmOrderPayment, refundDone, restoreOrder } from '../lib/orderActions'
import { playCallAlert, playOrderAlert, stopAlertSounds, turnAlertsOff, turnAlertsOn, vibrate } from '../lib/staffAlert'
import OrderStatusPage from './OrderStatusPage'
import type { OrderStatus, OrderSummary } from '../types/dashboard'

// DOM 없이 페이지를 함수로 굴리는 최소 harness (AccountPage.test.tsx와 같은 방식).
// useState 목이 setter를 즉시 반영하므로, setState 뒤 render()를 다시 부르면 바뀐 값으로 그려진다.
const hooks = vi.hoisted(() => ({
  values: [] as unknown[], cursor: 0, mounted: false,
  effects: [] as (() => void | (() => void))[],
  // 효과가 돌려준 정리 함수 — unmount()가 한꺼번에 부른다
  cleanups: [] as (() => void)[],
  alertPreferred: false,
  prefListener: null as ((on: boolean) => void) | null,
}))
vi.mock('react', async () => ({
  ...await vi.importActual<typeof import('react')>('react'),
  useState: (initial: unknown) => {
    const index = hooks.cursor++
    if (!(index in hooks.values)) hooks.values[index] = typeof initial === 'function' ? (initial as () => unknown)() : initial
    return [hooks.values[index], (value: unknown) => {
      hooks.values[index] = typeof value === 'function' ? (value as (p: unknown) => unknown)(hooks.values[index]) : value
    }]
  },
  useEffect: (effect: () => void | (() => void)) => { if (!hooks.mounted) hooks.effects.push(effect) },
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
  isAlertPreferred: () => hooks.alertPreferred,
  listenForAudioUnlock: vi.fn(() => () => {}),
  playOrderAlert: vi.fn(),
  playCallAlert: vi.fn(),
  releaseWakeLock: vi.fn(),
  resumeAudio: vi.fn(),
  stopAlertSounds: vi.fn(),
  // 설정 화면·다른 탭에서 켜고 끈 것을 흉내내려고 구독한 함수를 잡아 둔다
  subscribeAlertPreference: vi.fn((listener: (on: boolean) => void) => {
    hooks.prefListener = listener
    return () => {}
  }),
  suspendAudio: vi.fn(),
  turnAlertsOff: vi.fn(),
  turnAlertsOn: vi.fn(),
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
  additionalOrder?: boolean
  // 알림 빠른 전환(AlertSwitch)
  on?: boolean
  onToggle?: () => void
  label?: string
}

/** 알림 빠른 전환 스위치 — 주문현황에는 하나뿐이다 */
function alertSwitch() {
  return collect(render(), p => !!p.onToggle)[0]
}

function render() {
  hooks.cursor = 0
  const tree = OrderStatusPage()
  hooks.mounted = true
  hooks.effects.splice(0).forEach(effect => {
    const cleanup = effect()
    if (typeof cleanup === 'function') hooks.cleanups.push(cleanup)
  })
  return tree
}
/** 화면을 떠난다 — 지금까지 돌린 효과의 정리 함수를 모두 부른다 */
function unmount() {
  hooks.cleanups.splice(0).forEach(cleanup => cleanup())
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
/** 탭 버튼을 누른다 — 라벨로 찾는다. 완료·취소 탭은 누를 때 그 목록을 받으므로 응답 반영까지 한 틱 기다린다 */
async function clickTab(label: string) {
  const tab = collect(render(), p => {
    const kids = Children.toArray(p.children)
    return !!p.onClick && kids.some(k => typeof k === 'string' && k === label)
  })[0]
  tab!.onClick!()
  await new Promise((resolve) => setTimeout(resolve, 10))
}

// orderId는 orderNo의 숫자부만 따서 만든다("R-1"→1) — 그런데 "D-1"·"C-1"도 같은 1이 나와 상태(탭)가
// 다른 픽스처끼리 orderId/sessionId가 우연히 겹친다. sessionId는 그 숫자와 무관하게 독립적으로 늘려서
// (이 파일의 픽스처들은 서로 다른 테이블의 별개 주문을 흉내낸다) "추가 주문" 배지가 우연히 잡히지 않게 한다.
// "추가 주문" 배지 자체는 아래 전용 describe에서 별도로 sessionId를 맞춰 테스트한다.
let nextSessionId = 1000
function order(orderNo: string, createdAt: string, status: OrderStatus): OrderSummary {
  return {
    orderId: Number(orderNo.replace(/\D/g, '')),
    orderNo, status,
    paymentStatus: 'UNPAID', paymentMethod: null,
    // 메뉴 항목이 하나는 있어야 "추가 주문" 배지 대상이 된다 — 자릿세·기타 항목만 든 주문은 세지 않는다
    totalAmount: 1000,
    items: [{ itemId: 1, menuId: 1, menuName: '메뉴', unitPrice: 1000, qty: 1, itemType: 'MENU' }],
    createdAt,
    tableLabel: 'A-1', manual: false, sessionId: nextSessionId++,
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
// 탭 제목(document.title)을 보려고 최소한만 세운다
const fakeDocument = {
  title: '부스락',
  visibilityState: 'visible',
  addEventListener: () => {},
  removeEventListener: () => {},
}
vi.stubGlobal('document', fakeDocument)
vi.stubGlobal('window', {
  confirm: (message: string) => { confirmMessages.push(message); return confirmAnswer },
  localStorage: storage,
  addEventListener: () => {},
  removeEventListener: () => {},
})
afterAll(() => { vi.unstubAllGlobals() })

beforeEach(() => {
  hooks.values = []
  hooks.cursor = 0
  hooks.mounted = false
  hooks.effects = []
  unmount()
  hooks.alertPreferred = false
  fakeDocument.title = '부스락'
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
  it('기본 진입 탭은 진행(승인대기+접수)이고, 승인대기를 항상 앞에 — 각 묶음은 먼저 들어온 주문이 위 (O28)', async () => {
    await load()
    expect(renderedOrderNos()).toEqual(['P-1', 'P-2', 'R-1', 'R-2', 'R-3'])
  })

  it('승인대기 탭은 따로 없다 — 진행 탭 하나로 합쳤다', async () => {
    await load()
    const tabLabels = collect(render(), p => !!p.onClick)
      .flatMap(p => Children.toArray(p.children).filter(k => typeof k === 'string'))
    expect(tabLabels).toEqual(expect.arrayContaining(['진행', '완료', '취소']))
    expect(tabLabels).not.toContain('승인 대기')
  })

  it('완료 탭으로 옮기면 서버 순서(접수 최신 먼저) 그대로 그린다', async () => {
    await load()
    await clickTab('완료')
    expect(renderedOrderNos()).toEqual(['D-2', 'D-1'])
  })

  it('진행 탭을 보는 동안에는 완료·취소 전체 목록을 받지 않는다(저녁 폴링 부하) — 완료는 지금 세션 것만', async () => {
    await load()
    const paths = vi.mocked(apiFetch).mock.calls.map(([path]) => String(path))
    expect(paths.some((p) => p.includes('status=CANCELED'))).toBe(false)
    expect(paths.filter((p) => p.includes('status=DONE')).every((p) => p.includes('activeSessionOnly=true'))).toBe(true)
  })

  it('완료·취소 건수는 그 탭을 보고 있을 때만 표시한다(안 볼 때는 목록을 안 받아 숫자가 멈추므로)', async () => {
    const countOf = (label: string) => {
      const tab = collect(render(), p => !!p.onClick && Children.toArray(p.children).some(k => k === label))[0]
      return Children.toArray(tab!.children).filter(k => typeof k === 'number')
    }
    await load()
    expect(countOf('완료')).toEqual([])
    await clickTab('완료')
    expect(countOf('완료')).toEqual([2])
    await clickTab('진행')
    expect(countOf('완료')).toEqual([])
  })

  it('취소 탭도 서버 순서 그대로다', async () => {
    await load()
    await clickTab('취소')
    expect(renderedOrderNos()).toEqual(['C-1', 'C-2'])
  })

  it('완료 탭에 갔다가 진행 탭으로 돌아와도 진행 탭 정렬이 유지된다', async () => {
    await load()
    await clickTab('완료')
    await clickTab('진행')
    expect(renderedOrderNos()).toEqual(['P-1', 'P-2', 'R-1', 'R-2', 'R-3'])
  })

  it('폴링으로 목록이 바뀌면 바뀐 목록을 다시 정렬해 그린다', async () => {
    await load()
    await clickTab('진행')
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
    await clickTab('완료')
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
    expect(confirmMessages[0]).toBe('이 주문을 거절할까요?')   // 입금 경고는 붙이지 않는다(2026-09-28)
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
    await clickTab('취소')
    expect(cards()[1].onRefundDone).toBeTypeOf('function')
  })

  it('STAFF에게는 붙지 않는다 — 눌러도 백엔드가 403이다', async () => {
    localStorage.setItem('boothlock_staff', JSON.stringify({ role: 'STAFF', boothId: 1, boothName: '테스트' }))
    await load()
    await clickTab('취소')
    expect(cards()[1].onRefundDone).toBeUndefined()
  })

  it('확인을 거쳐야 요청이 나간다', async () => {
    localStorage.setItem('boothlock_staff', JSON.stringify({ role: 'ADMIN', boothId: 1, boothName: '테스트' }))
    await load()
    await clickTab('취소')
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
  // 폴링 조회(승인대기·진행·지금 세션 완료) 셋이 모두 나간 뒤, 응답 파싱(json)·상태 반영까지 끝나도록 한 틱 더 기다린다
  await vi.waitFor(() => expect(vi.mocked(apiFetch)).toHaveBeenCalledTimes(3))
  await new Promise((resolve) => setTimeout(resolve, 10))
}

describe('알림 — 주문현황의 몫(빠른 켜고 끔·탭 제목). 새 주문·호출 감시와 소리는 앱 공통 StaffAlertWatcher', () => {
  // 알림 켜기를 눌러 둔 운영자 기준 — 꺼져 있을 때는 아래 [알림 끔] 테스트가 본다
  beforeEach(() => { hooks.alertPreferred = true })

  it('새 주문·호출이 생겨도 주문현황은 직접 소리·진동을 내지 않는다 — 공통 감시와 겹쳐 두 번 울리지 않게', async () => {
    await load()
    vi.mocked(apiFetch).mockClear()
    await pollWith(
      [order('P-3', '2026-09-21T19:00:00', 'PENDING_APPROVAL'), ...PENDING_APPROVAL],
      [{ callId: 9, tableLabel: 'A-1', reason: 'HELP', createdAt: '2026-09-21T19:00:00' }],
    )
    expect(vi.mocked(playOrderAlert)).not.toHaveBeenCalled()
    expect(vi.mocked(playCallAlert)).not.toHaveBeenCalled()
    expect(vi.mocked(vibrate)).not.toHaveBeenCalled()
    // 화면 표시(탭 제목의 승인대기 수)는 그대로 이 화면이 갱신한다
    expect(fakeDocument.title).toContain('3')
  })

  it('화면을 떠나도 이미 예약된 알림음은 끊지 않는다 — 들어온 주문·호출의 소리는 끝까지 울린다', async () => {
    await load()
    unmount()
    expect(vi.mocked(stopAlertSounds)).not.toHaveBeenCalled()
    expect(vi.mocked(turnAlertsOff)).not.toHaveBeenCalled()
  })

  it('알림을 끄면 예약된 알림음을 끊는다 — 끄기는 설정 화면과 같은 turnAlertsOff 한 곳', async () => {
    await load()
    alertSwitch()!.onToggle!()
    expect(vi.mocked(turnAlertsOff)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(turnAlertsOn)).not.toHaveBeenCalled()
  })

  it('알림을 켜면 확인음을 내는 turnAlertsOn — 폴링용 주문 알림음을 바로 부르지 않는다', async () => {
    hooks.alertPreferred = false
    await load()
    alertSwitch()!.onToggle!()
    expect(vi.mocked(turnAlertsOn)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(playOrderAlert)).not.toHaveBeenCalled()
  })

  it('빠른 전환은 지금 상태를 글자로도 보여 준다 — 켜짐 "🔔 알림 ON", 꺼짐 "🔕 알림 OFF"', async () => {
    await load()
    expect(alertSwitch()).toMatchObject({ on: true, label: '🔔 알림 ON' })
    alertSwitch()!.onToggle!()
    expect(alertSwitch()).toMatchObject({ on: false, label: '🔕 알림 OFF' })
  })

  it('설정 화면·다른 탭에서 끄면 스위치가 따라서 꺼진다 — 소리 정리는 공통 감시(StaffAlertWatcher) 몫', async () => {
    await load()
    hooks.prefListener!(false)
    expect(alertSwitch()).toMatchObject({ on: false, label: '🔕 알림 OFF' })
    expect(vi.mocked(stopAlertSounds)).not.toHaveBeenCalled()
  })

  it('설정 화면·다른 탭에서 켜면 스위치가 따라서 켜진다', async () => {
    hooks.alertPreferred = false
    await load()
    hooks.prefListener!(true)
    expect(alertSwitch()).toMatchObject({ on: true, label: '🔔 알림 ON' })
  })

  it('알림을 꺼도 탭 제목 숫자는 그대로 갱신한다', async () => {
    await load()
    alertSwitch()!.onToggle!()
    vi.mocked(apiFetch).mockClear()
    await pollWith([order('P-3', '2026-09-21T19:00:00', 'PENDING_APPROVAL'), ...PENDING_APPROVAL], [])
    expect(fakeDocument.title).toContain('3')
  })

  it('화면을 떠난 뒤 도착한 폴링 응답은 탭 제목을 바꾸지 않는다', async () => {
    await load()
    vi.mocked(apiFetch).mockClear()
    // 응답을 붙잡아 두고, 요청이 나간 상태에서 화면을 떠난다
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    const pending = [order('P-3', '2026-09-21T19:00:00', 'PENDING_APPROVAL'), ...PENDING_APPROVAL]
    vi.mocked(apiFetch).mockImplementation(async (path) => {
      await gate
      const orders = String(path).includes('status=PENDING_APPROVAL') ? pending : []
      return new Response(JSON.stringify({ orders, calls: [] }), { status: 200 })
    })
    hooks.effects.splice(0)
    hooks.mounted = false
    render()
    await vi.waitFor(() => expect(vi.mocked(apiFetch)).toHaveBeenCalledTimes(3))
    unmount()
    expect(fakeDocument.title).toBe('부스락')
    release()
    await new Promise((resolve) => setTimeout(resolve, 10))
    expect(fakeDocument.title).toBe('부스락')
    expect(vi.mocked(playOrderAlert)).not.toHaveBeenCalled()
    expect(vi.mocked(playCallAlert)).not.toHaveBeenCalled()
    expect(vi.mocked(vibrate)).not.toHaveBeenCalled()
  })
})

describe('추가 주문 배지', () => {
  it('같은 테이블 세션의 후속 주문이 다른 탭(완료)에 첫 주문을 두고 있어도 배지를 표시한다', async () => {
    const first = { ...order('X-1', '2026-09-21T10:00:00', 'DONE'), sessionId: 9 }
    const second = { ...order('X-2', '2026-09-21T11:00:00', 'RECEIVED'), sessionId: 9 }
    vi.mocked(apiFetch).mockImplementation(async (path) => {
      const s = String(path)
      const orders = s.includes('status=RECEIVED') ? [second] : s.includes('status=DONE') ? [first] : []
      return new Response(JSON.stringify({ orders, calls: [] }), { status: 200 })
    })
    render()
    await vi.waitFor(() => expect(cards().length).toBeGreaterThan(0))
    expect(cards()[0].additionalOrder).toBe(true)
  })

  it('세션이 다르면(테이블이 다르거나 별개 세션) 배지가 없다', async () => {
    await load()
    expect(cards().every((c) => !c.additionalOrder)).toBe(true)
  })
})

describe('결제 확인 (O11)', () => {
  // 카드의 결제 확인 버튼은 없앴다(2026-09-28) — 입금 기록은 테이블 화면 결제 모달(O24)에서 한다
  it('주문 카드에 결제 확인 수단을 넘기지 않는다', async () => {
    await load()
    expect(cards().every(c => !c.onConfirmPayment)).toBe(true)
    expect(vi.mocked(confirmOrderPayment)).not.toHaveBeenCalled()
  })
})
