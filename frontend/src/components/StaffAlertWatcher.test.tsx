import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../lib/apiFetch'
import {
  acquireWakeLock,
  listenForAudioUnlock,
  playCallAlert,
  playOrderAlert,
  stopAlertSounds,
  suspendAudio,
  vibrate,
} from '../lib/staffAlert'
import { isStaffPath } from '../lib/staffAlertWatcher'
import StaffAlertWatcher from './StaffAlertWatcher'

// DOM 없이 컴포넌트를 함수로 굴리는 harness — 이 테스트는 "화면 이동에도 감시가 한 벌로 유지되는가"를 보므로
// useEffect가 React처럼 의존성이 바뀔 때만 정리→재실행되게 흉내낸다(다른 페이지 테스트의 harness보다 한 단계 더)
const hooks = vi.hoisted(() => ({
  values: [] as unknown[],
  deps: [] as (unknown[] | undefined)[],
  cleanups: [] as ((() => void) | undefined)[],
  cursor: 0,
  pathname: '/settings',
  token: 'jwt' as string | null,
  alertPreferred: true,
  prefListener: null as ((on: boolean) => void) | null,
  server: { orders: [] as number[], calls: [] as number[] },
  // 응답 Date 헤더(null이면 안 보냄)와 id별 "생긴 지 몇 초"(없으면 방금 생김)
  dateHeader: null as string | null,
  orderAgeSeconds: {} as Record<number, number>,
  callAgeSeconds: {} as Record<number, number>,
  // 감시가 등록한 화면 복귀·재연결 리스너(lib/onResume 대역)
  resumeListener: null as ((reason: 'visible' | 'online') => void) | null,
}))
// 서버 시각 — Date 헤더는 초 단위라 딱 떨어지는 값으로
const SERVER_NOW = Date.parse('2026-09-29T03:00:00Z')
// 생긴 시각은 서버가 주는 모양 그대로 — KST 오프셋·소수점 6자리(OffsetDateTime, MICROS 절삭)
const createdAt = (ageSeconds = 0) =>
  new Date(SERVER_NOW - ageSeconds * 1000 + 9 * 3600_000).toISOString().replace('Z', '123+09:00')
const resume = (reason: 'visible' | 'online') => hooks.resumeListener!(reason)
vi.mock('react', async () => ({
  ...await vi.importActual<typeof import('react')>('react'),
  useState: (initial: unknown) => {
    const index = hooks.cursor++
    if (!(index in hooks.values)) hooks.values[index] = typeof initial === 'function' ? (initial as () => unknown)() : initial
    return [hooks.values[index], (value: unknown) => {
      hooks.values[index] = typeof value === 'function' ? (value as (p: unknown) => unknown)(hooks.values[index]) : value
    }]
  },
  useRef: (initial: unknown) => {
    const index = hooks.cursor++
    if (!(index in hooks.values)) hooks.values[index] = { current: initial }
    return hooks.values[index]
  },
  useEffect: (effect: () => void | (() => void), deps?: unknown[]) => {
    const index = hooks.cursor++
    const prev = hooks.deps[index]
    const changed = !prev || !deps || deps.some((d, k) => !Object.is(d, prev[k]))
    if (!changed) return
    hooks.cleanups[index]?.()
    hooks.deps[index] = deps
    const cleanup = effect()
    hooks.cleanups[index] = typeof cleanup === 'function' ? cleanup : undefined
  },
}))
vi.mock('react-router-dom', () => ({ useLocation: () => ({ pathname: hooks.pathname }) }))
vi.mock('../lib/auth', () => ({ getAuthToken: () => hooks.token }))
vi.mock('../lib/apiFetch', () => ({ apiFetch: vi.fn() }))
vi.mock('../lib/onResume', () => ({
  onResume: (listener: (reason: 'visible' | 'online') => void) => {
    hooks.resumeListener = listener
    return () => { if (hooks.resumeListener === listener) hooks.resumeListener = null }
  },
}))
vi.mock('../lib/staffAlert', () => ({
  acquireWakeLock: vi.fn(),
  isAlertPreferred: () => hooks.alertPreferred,
  listenForAudioUnlock: vi.fn(() => () => {}),
  playCallAlert: vi.fn(),
  playOrderAlert: vi.fn(),
  releaseWakeLock: vi.fn(),
  resumeAudio: vi.fn(),
  stopAlertSounds: vi.fn(),
  subscribeAlertPreference: vi.fn((listener: (on: boolean) => void) => {
    hooks.prefListener = listener
    return () => {}
  }),
  suspendAudio: vi.fn(),
  vibrate: vi.fn(),
}))

// 동시에 도는 감시는 모듈 전체에서 하나뿐이다(staffAlertWatcher) — 테스트 사이에 남지 않게 매번 정리한다
function render() {
  hooks.cursor = 0
  return StaffAlertWatcher()
}
function unmountAll() {
  hooks.cleanups.forEach(cleanup => cleanup?.())
  hooks.cleanups = []
  hooks.deps = []
}
/** 경로를 바꾼다 — App 안의 감시는 라우트가 바뀌어도 같은 인스턴스라 다시 그려지기만 한다 */
function navigate(pathname: string) {
  hooks.pathname = pathname
  render()
}
async function tick(ms = 5000) {
  await vi.advanceTimersByTimeAsync(ms)
}
const polls = () => vi.mocked(apiFetch).mock.calls.length

beforeEach(() => {
  vi.useFakeTimers()
  hooks.values = []
  hooks.deps = []
  hooks.cleanups = []
  hooks.pathname = '/settings'
  hooks.token = 'jwt'
  hooks.alertPreferred = true
  hooks.server = { orders: [1], calls: [] }
  hooks.dateHeader = null
  hooks.orderAgeSeconds = {}
  hooks.callAgeSeconds = {}
  hooks.resumeListener = null
  vi.clearAllMocks()
  vi.mocked(apiFetch).mockImplementation(async () => new Response(JSON.stringify({
    orders: hooks.server.orders.map(orderId => ({ orderId, createdAt: createdAt(hooks.orderAgeSeconds[orderId]) })),
    calls: hooks.server.calls.map(callId => ({ callId, createdAt: createdAt(hooks.callAgeSeconds[callId]) })),
  }), { status: 200, headers: hooks.dateHeader === null ? {} : { Date: hooks.dateHeader } }))
})
afterEach(() => {
  unmountAll()
  vi.useRealTimers()
})

describe('직원 화면 판별', () => {
  it('주문현황·테이블·설정(하위 포함)만 직원 화면이다 — 손님·방문자·로그인 화면은 아니다', () => {
    for (const path of ['/orders', '/tables', '/settings', '/settings/menu', '/settings/table-qr']) {
      expect(isStaffPath(path)).toBe(true)
    }
    for (const path of ['/', '/signup', '/home', '/scan', '/t/abc', '/order', '/cart', '/order-history', '/payment-info', '/ordersx']) {
      expect(isStaffPath(path)).toBe(false)
    }
  })
})

describe('어느 직원 화면에서든 울린다 (StaffAlertWatcher)', () => {
  it('주문현황이 아닌 설정 화면에서도 새 주문은 주문음, 새 호출은 호출음 — 승인대기 조회 하나로 둘 다 본다', async () => {
    render()
    await tick(0)
    expect(vi.mocked(apiFetch)).toHaveBeenCalledWith('/api/v1/admin/orders?status=PENDING_APPROVAL')
    expect(playOrderAlert).not.toHaveBeenCalled() // 첫 조회는 알리지 않는다
    hooks.server = { orders: [1, 2], calls: [] }
    await tick()
    expect(playOrderAlert).toHaveBeenCalledTimes(1)
    hooks.server = { orders: [1, 2], calls: [5] }
    await tick()
    expect(playCallAlert).toHaveBeenCalledTimes(1)
    expect(vibrate).toHaveBeenCalledTimes(2)
  })

  it('직원 화면끼리 오가도 감시는 한 벌 — 5초에 조회 한 번, 같은 주문은 한 번만 울린다', async () => {
    navigate('/orders')
    await tick(0)
    expect(polls()).toBe(1)
    for (const path of ['/settings', '/tables', '/orders', '/settings', '/settings/menu']) navigate(path)
    await tick(0)
    expect(polls()).toBe(1) // 이동만으로는 새로 조회하지 않는다(감시가 다시 시작되지 않았다)
    hooks.server = { orders: [1, 2], calls: [] }
    await tick()
    expect(polls()).toBe(2)
    navigate('/orders')
    navigate('/settings')
    await tick()
    await tick()
    expect(polls()).toBe(4)
    expect(playOrderAlert).toHaveBeenCalledTimes(1)
  })

  it('주문과 호출이 한 주기에 같이 오면 두 소리를 한 번씩(겹치지 않게 잇는 건 staffAlert), 진동은 한 번', async () => {
    render()
    await tick(0)
    hooks.server = { orders: [1, 2], calls: [5] }
    await tick()
    expect(playOrderAlert).toHaveBeenCalledTimes(1)
    expect(playCallAlert).toHaveBeenCalledTimes(1)
    expect(vibrate).toHaveBeenCalledTimes(1)
  })

  it('손님·방문자 화면이나 로그인 전에는 감시하지 않는다 — 토큰 없이 apiFetch를 부르면 로그인 화면으로 튕긴다', async () => {
    hooks.pathname = '/order'
    render()
    await tick()
    hooks.pathname = '/settings'
    hooks.token = null
    render()
    await tick()
    expect(apiFetch).not.toHaveBeenCalled()
  })

  it('로그아웃(토큰 삭제 뒤 로그인 화면)이면 멈춘다 — 다시 로그인하면 첫 조회부터라 쌓인 것을 한꺼번에 울리지 않는다', async () => {
    render()
    await tick(0)
    hooks.token = null
    navigate('/')
    const before = polls()
    await tick()
    await tick()
    expect(polls()).toBe(before)
    hooks.server = { orders: [1, 2, 3], calls: [4] }
    hooks.token = 'jwt'
    navigate('/orders')
    await tick(0)
    await tick()
    expect(playOrderAlert).not.toHaveBeenCalled()
    expect(playCallAlert).not.toHaveBeenCalled()
  })
})

describe('탭 복귀 직후 따라잡기 조회에서만 늦은 것을 울리지 않는다 (서버 Date 헤더 기준)', () => {
  beforeEach(() => { hooks.dateHeader = new Date(SERVER_NOW).toUTCString() })

  it('숨겨졌던 탭이 돌아온 첫 조회 — 오래된 주문·호출은 울리지 않고, 20초 안에 생긴 호출만 울린다', async () => {
    render()
    await tick(0)
    // 탭이 뒤에 있는 동안 생긴 주문 2(1분 전)·호출 5(40초 전), 돌아오기 직전 누른 호출 6(3초 전)
    hooks.server = { orders: [1, 2], calls: [5, 6] }
    hooks.orderAgeSeconds = { 2: 60 }
    hooks.callAgeSeconds = { 5: 40, 6: 3 }
    resume('visible')
    await tick(0)
    expect(playOrderAlert).not.toHaveBeenCalled()
    expect(playCallAlert).toHaveBeenCalledTimes(1)
    expect(vibrate).toHaveBeenCalledTimes(1)
  })

  it('복귀 뒤 다음 주기부터는 평소대로 — 20초 넘은 새 주문이라도 울린다(무음 처리한 것은 다시 울리지 않는다)', async () => {
    render()
    await tick(0)
    hooks.server = { orders: [1, 2], calls: [] }
    hooks.orderAgeSeconds = { 2: 60 }
    resume('visible')
    await tick(0)
    expect(playOrderAlert).not.toHaveBeenCalled()
    hooks.server = { orders: [1, 2, 3], calls: [] }
    hooks.orderAgeSeconds = { 2: 65, 3: 25 }
    await tick()
    expect(playOrderAlert).toHaveBeenCalledTimes(1)
    await tick()
    expect(playOrderAlert).toHaveBeenCalledTimes(1)
  })

  it('화면을 보는 중 조회가 실패했다 복구되면 20초 넘은 것도 울린다', async () => {
    render()
    await tick(0)
    vi.mocked(apiFetch).mockRejectedValueOnce(new Error('timeout')).mockRejectedValueOnce(new Error('timeout'))
    await tick()
    await tick()
    hooks.server = { orders: [1, 2], calls: [5] }
    hooks.orderAgeSeconds = { 2: 25 }
    hooks.callAgeSeconds = { 5: 40 }
    await tick()
    expect(playOrderAlert).toHaveBeenCalledTimes(1)
    expect(playCallAlert).toHaveBeenCalledTimes(1)
  })

  it('평소 주기 조회는 20초 넘게 늦게 알게 된 것도 울린다 — 숨겨진 탭에서 느려진 주기 포함', async () => {
    render()
    await tick(0)
    hooks.server = { orders: [1, 2], calls: [] }
    hooks.orderAgeSeconds = { 2: 50 }
    await tick()
    expect(playOrderAlert).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['Date 헤더 없음', null],
    ['Date 헤더를 못 읽음', 'not-a-date'],
  ])('%s이면 따라잡기 조회라도 가리지 않고 울린다 — 새 알림을 놓치지 않게', async (_label, header) => {
    hooks.dateHeader = header
    render()
    await tick(0)
    hooks.server = { orders: [1, 2], calls: [5] }
    hooks.orderAgeSeconds = { 2: 300 }
    hooks.callAgeSeconds = { 5: 300 }
    resume('visible')
    await tick(0)
    expect(playOrderAlert).toHaveBeenCalledTimes(1)
    expect(playCallAlert).toHaveBeenCalledTimes(1)
  })
})

describe('알림 끔 = 소리만 끔 (감시는 계속)', () => {
  it('끈 동안에도 조회는 계속하고 소리·진동만 내지 않는다. 다시 켜도 끈 동안 생긴 것은 울리지 않고, 그 뒤 새 것부터 울린다', async () => {
    render()
    await tick(0)
    hooks.prefListener!(false)
    render()
    hooks.server = { orders: [1, 2], calls: [5] }
    const before = polls()
    await tick()
    expect(polls()).toBe(before + 1)
    expect(playOrderAlert).not.toHaveBeenCalled()
    expect(playCallAlert).not.toHaveBeenCalled()
    expect(vibrate).not.toHaveBeenCalled()

    hooks.prefListener!(true)
    render()
    await tick()
    expect(playOrderAlert).not.toHaveBeenCalled()
    hooks.server = { orders: [1, 2, 3], calls: [5] }
    await tick()
    expect(playOrderAlert).toHaveBeenCalledTimes(1)
    expect(playCallAlert).not.toHaveBeenCalled()
  })

  it('다른 화면·탭에서 끄면 이 탭에 예약된 소리를 끊고 오디오를 재운다(#126 안전장치)', () => {
    render()
    hooks.prefListener!(false)
    expect(stopAlertSounds).toHaveBeenCalledTimes(1)
    expect(suspendAudio).toHaveBeenCalledTimes(1)
  })
})

describe('화면 꺼짐 방지·오디오 풀기도 어느 직원 화면에서든', () => {
  // node 환경에는 document가 없다 — 이 효과가 쓰는 것만 세운다
  beforeEach(() => {
    vi.stubGlobal('document', { visibilityState: 'visible', addEventListener: () => {}, removeEventListener: () => {} })
  })
  afterEach(() => { vi.unstubAllGlobals() })

  it('알림이 켜져 있으면 설정 화면에서도 한 번 누르면 소리가 풀리게 기다린다 — 손님 화면에서는 하지 않는다', () => {
    render()
    expect(acquireWakeLock).toHaveBeenCalledTimes(1)
    expect(listenForAudioUnlock).toHaveBeenCalledTimes(1)
    navigate('/tables')
    expect(listenForAudioUnlock).toHaveBeenCalledTimes(1) // 직원 화면끼리 이동은 다시 걸지 않는다
    navigate('/cart')
    vi.clearAllMocks()
    render()
    expect(listenForAudioUnlock).not.toHaveBeenCalled()
  })
})
