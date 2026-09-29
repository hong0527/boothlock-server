import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ArrivalSnapshot, Arrivals } from './newArrivals'
import type { ResumeReason } from './onResume'

// 공통 알림 감시 — 모듈 상태(동시에 도는 감시 하나)를 테스트마다 새로 받는다
async function load() {
  vi.resetModules()
  return import('./staffAlertWatcher')
}

const snap = (orders: number[], calls: number[] = []): ArrivalSnapshot => ({
  pendingOrderIds: new Set(orders),
  callIds: new Set(calls),
})

// 서버 시각과 id별 생긴 시각([id, 몇 초 전])을 담은 스냅샷
const NOW = Date.parse('2026-09-29T12:00:00+09:00')
const timed = (serverNow: number, orders: [number, number][], calls: [number, number][] = []): ArrivalSnapshot => ({
  pendingOrderIds: new Set(orders.map(([id]) => id)),
  callIds: new Set(calls.map(([id]) => id)),
  serverNow,
  orderCreatedAt: new Map(orders.map(([id, ago]) => [id, serverNow - ago * 1000])),
  callCreatedAt: new Map(calls.map(([id, ago]) => [id, serverNow - ago * 1000])),
})

/** 서버 상태를 흉내낸다 — current를 바꾸면 다음 조회부터 그 값이 나온다 */
function fakeServer(initial: ArrivalSnapshot) {
  const server = { current: initial, calls: 0 }
  const fetchSnapshot = vi.fn(async () => {
    server.calls += 1
    return server.current
  })
  return { server, fetchSnapshot }
}

/** 한 주기 진행 — 타이머를 넘기고 조회 응답(마이크로태스크)까지 처리한다 */
async function tick(ms = 5000) {
  await vi.advanceTimersByTimeAsync(ms)
}

beforeEach(() => { vi.useFakeTimers() })
afterEach(() => { vi.useRealTimers() })

describe('공통 알림 감시 (staffAlertWatcher)', () => {
  it('5초마다 한 번 조회하고, 첫 조회에 이미 있던 것은 알리지 않는다', async () => {
    const { createArrivalWatcher } = await load()
    const { server, fetchSnapshot } = fakeServer(snap([1, 2], [7]))
    const onArrivals = vi.fn()
    const watcher = createArrivalWatcher({ fetchSnapshot, onArrivals })
    watcher.start()
    await tick(0)
    expect(server.calls).toBe(1)
    await tick()
    await tick()
    expect(server.calls).toBe(3)
    expect(onArrivals).not.toHaveBeenCalled()
    watcher.stop()
  })

  it('새 주문·새 호출은 생긴 주기에 한 번만 알리고, 계속 보여도 다시 알리지 않는다', async () => {
    const { createArrivalWatcher } = await load()
    const { server, fetchSnapshot } = fakeServer(snap([1]))
    const seen: Arrivals[] = []
    const watcher = createArrivalWatcher({ fetchSnapshot, onArrivals: (a) => seen.push(a) })
    watcher.start()
    await tick(0)
    server.current = snap([1, 2], [9])
    await tick()
    await tick()
    await tick()
    expect(seen).toEqual([{ newPendingOrders: 1, newCalls: 1 }])
    watcher.stop()
  })

  it('앞 조회가 아직 안 끝났으면 다음 주기는 건너뛴다 — 느린 회선에서 요청이 쌓이지 않게', async () => {
    const { createArrivalWatcher } = await load()
    let release!: () => void
    const fetchSnapshot = vi.fn(() => new Promise<ArrivalSnapshot>((resolve) => { release = () => resolve(snap([])) }))
    const watcher = createArrivalWatcher({ fetchSnapshot, onArrivals: vi.fn() })
    watcher.start()
    await tick()
    await tick()
    expect(fetchSnapshot).toHaveBeenCalledTimes(1)
    release()
    await tick()
    expect(fetchSnapshot).toHaveBeenCalledTimes(2)
    watcher.stop()
  })

  it('조회가 실패한 주기는 건너뛰고, 그 사이 생긴 것은 다음 성공 때 알린다', async () => {
    const { createArrivalWatcher } = await load()
    const { server, fetchSnapshot } = fakeServer(snap([1]))
    const onArrivals = vi.fn()
    const watcher = createArrivalWatcher({ fetchSnapshot, onArrivals })
    watcher.start()
    await tick(0)
    fetchSnapshot.mockRejectedValueOnce(new Error('network'))
    server.current = snap([1, 2])
    await tick()
    expect(onArrivals).not.toHaveBeenCalled()
    await tick()
    expect(onArrivals).toHaveBeenCalledWith({ newPendingOrders: 1, newCalls: 0 })
    watcher.stop()
  })

  it('화면을 보는 중 조회가 한동안 실패했다 복구되면, 그 사이 생긴 것은 20초가 넘었어도 알린다', async () => {
    const { createArrivalWatcher } = await load()
    const { server, fetchSnapshot } = fakeServer(timed(NOW, [[1, 100]]))
    const onArrivals = vi.fn()
    const watcher = createArrivalWatcher({ fetchSnapshot, onArrivals })
    watcher.start()
    await tick(0)
    // 여섯 주기 연속 실패(약 30초 끊김)
    for (let i = 0; i < 6; i += 1) fetchSnapshot.mockRejectedValueOnce(new Error('network'))
    for (let i = 0; i < 6; i += 1) await tick()
    expect(onArrivals).not.toHaveBeenCalled()
    // 복구 — 끊긴 동안 생긴 주문 2(28초 전)·호출 8(40초 전)도 알린다
    server.current = timed(NOW + 35_000, [[1, 135], [2, 28]], [[8, 40]])
    await tick()
    expect(onArrivals).toHaveBeenCalledTimes(1)
    expect(onArrivals).toHaveBeenCalledWith({ newPendingOrders: 1, newCalls: 1 })
    watcher.stop()
  })

  it('stop하면 조회를 멈추고, 멈춘 뒤 도착한 응답으로는 알리지 않는다', async () => {
    const { createArrivalWatcher } = await load()
    let release!: (s: ArrivalSnapshot) => void
    const fetchSnapshot = vi.fn()
      .mockResolvedValueOnce(snap([1]))
      .mockImplementationOnce(() => new Promise<ArrivalSnapshot>((resolve) => { release = resolve }))
    const onArrivals = vi.fn()
    const watcher = createArrivalWatcher({ fetchSnapshot, onArrivals })
    watcher.start()
    await tick(0)
    await tick()
    watcher.stop()
    release(snap([1, 2]))
    await tick()
    await tick()
    expect(onArrivals).not.toHaveBeenCalled()
    expect(fetchSnapshot).toHaveBeenCalledTimes(2)
  })

  it('동시에 도는 감시는 하나뿐이다 — 두 번째 start는 아무것도 하지 않는다(폴링·알림음이 두 배가 되지 않게)', async () => {
    const { createArrivalWatcher } = await load()
    const a = fakeServer(snap([]))
    const b = fakeServer(snap([]))
    const first = createArrivalWatcher({ fetchSnapshot: a.fetchSnapshot, onArrivals: vi.fn() })
    const second = createArrivalWatcher({ fetchSnapshot: b.fetchSnapshot, onArrivals: vi.fn() })
    expect(first.start()).toBe(true)
    expect(first.start()).toBe(false)
    expect(second.start()).toBe(false)
    await tick()
    expect(b.server.calls).toBe(0)
    // 첫 감시가 멈추면 그때는 새로 시작할 수 있다
    first.stop()
    expect(second.start()).toBe(true)
    second.stop()
  })

  it('다시 시작하면 첫 조회부터 — 멈춘 동안(로그아웃 등) 쌓인 것을 한꺼번에 알리지 않는다', async () => {
    const { createArrivalWatcher } = await load()
    const { server, fetchSnapshot } = fakeServer(snap([1]))
    const onArrivals = vi.fn()
    const watcher = createArrivalWatcher({ fetchSnapshot, onArrivals })
    watcher.start()
    await tick(0)
    watcher.stop()
    server.current = snap([1, 2, 3])
    watcher.start()
    await tick(0)
    await tick()
    expect(onArrivals).not.toHaveBeenCalled()
    watcher.stop()
  })

  it('폰 잠금 해제·재연결(onResume)이면 주기를 기다리지 않고 바로 본다. stop하면 구독도 푼다', async () => {
    const { createArrivalWatcher } = await load()
    const { server, fetchSnapshot } = fakeServer(snap([1]))
    let resume: ((reason: ResumeReason) => void) | null = null
    const offResume = vi.fn()
    const onArrivals = vi.fn()
    const watcher = createArrivalWatcher({
      fetchSnapshot, onArrivals,
      onResume: (listener) => { resume = listener; return offResume },
    })
    watcher.start()
    await tick(0)
    server.current = snap([1, 2])
    resume!('visible')
    await tick(0)
    expect(onArrivals).toHaveBeenCalledTimes(1)
    watcher.stop()
    expect(offResume).toHaveBeenCalledTimes(1)
  })
})

describe('탭 복귀 따라잡기 조회에서만 늦은 것을 무음 처리 (onResume visible)', () => {
  /** 감시를 켜고 첫 조회까지 마친다 — resume(reason)으로 화면 복귀·재연결을 흉내낸다 */
  async function startWithResume(initial: ArrivalSnapshot) {
    const { createArrivalWatcher } = await load()
    const { server, fetchSnapshot } = fakeServer(initial)
    let resume!: (reason: ResumeReason) => void
    const onArrivals = vi.fn()
    const watcher = createArrivalWatcher({
      fetchSnapshot, onArrivals,
      onResume: (listener) => { resume = listener; return () => {} },
    })
    watcher.start()
    await tick(0)
    return { server, fetchSnapshot, onArrivals, watcher, resume: (reason: ResumeReason) => resume(reason) }
  }

  it('복귀 첫 조회에서 20초 넘은 것은 무음, 20초 안의 것은 알린다', async () => {
    const { server, onArrivals, watcher, resume } = await startWithResume(timed(NOW, [[1, 0]]))
    // 탭이 뒤에 있는 동안 생긴 주문 2(90초 전)·호출 8(40초 전), 돌아오기 직전 호출 9(3초 전)
    server.current = timed(NOW + 90_000, [[1, 90], [2, 90]], [[8, 40], [9, 3]])
    resume('visible')
    await tick(0)
    expect(onArrivals).toHaveBeenCalledTimes(1)
    expect(onArrivals).toHaveBeenCalledWith({ newPendingOrders: 0, newCalls: 1 })
    watcher.stop()
  })

  it('복귀 첫 조회에서 전부 오래된 것이면 아예 알리지 않는다', async () => {
    const { server, onArrivals, watcher, resume } = await startWithResume(timed(NOW, [[1, 0]]))
    server.current = timed(NOW + 90_000, [[1, 90], [2, 60]], [[8, 40]])
    resume('visible')
    await tick(0)
    expect(onArrivals).not.toHaveBeenCalled()
    watcher.stop()
  })

  it('복귀 뒤 다음 주기부터는 평소대로 — 20초 넘은 새 것이라도 알린다', async () => {
    const { server, onArrivals, watcher, resume } = await startWithResume(timed(NOW, [[1, 0]]))
    server.current = timed(NOW + 90_000, [[1, 90], [2, 60]])
    resume('visible')
    await tick(0)
    expect(onArrivals).not.toHaveBeenCalled()
    // 다음 주기에 새로 보인 주문 3은 (서버 쪽 지연 등으로) 30초 전 것이어도 알린다. 무음 처리한 2는 다시 세지 않는다
    server.current = timed(NOW + 95_000, [[1, 95], [2, 65], [3, 30]])
    await tick()
    expect(onArrivals).toHaveBeenCalledTimes(1)
    expect(onArrivals).toHaveBeenCalledWith({ newPendingOrders: 1, newCalls: 0 })
    watcher.stop()
  })

  it('재연결(online)로 도는 조회는 따라잡기가 아니다 — 20초 넘었어도 알린다', async () => {
    const { server, onArrivals, watcher, resume } = await startWithResume(timed(NOW, [[1, 0]]))
    server.current = timed(NOW + 60_000, [[1, 60], [2, 45]])
    resume('online')
    await tick(0)
    expect(onArrivals).toHaveBeenCalledWith({ newPendingOrders: 1, newCalls: 0 })
    watcher.stop()
  })

  it('복귀 순간 앞 조회가 아직이라 건너뛰면, 다음에 실제로 도는 조회가 따라잡기를 이어받는다', async () => {
    const { server, fetchSnapshot, onArrivals, watcher, resume } = await startWithResume(timed(NOW, [[1, 0]]))
    // 숨겨진 동안 시작한 조회가 아직 안 끝났다 — 그 응답은 평소 조회라 알린다(주문 2, 3초 전)
    let release!: (s: ArrivalSnapshot) => void
    fetchSnapshot.mockImplementationOnce(() => new Promise<ArrivalSnapshot>((resolve) => { release = resolve }))
    await tick()
    resume('visible') // 건너뜀
    release(timed(NOW + 5_000, [[1, 5], [2, 3]]))
    await tick(0)
    expect(onArrivals).toHaveBeenCalledTimes(1)
    // 다음 주기가 따라잡기 — 그 사이 알게 된 오래된 호출 8(50초 전)은 무음
    server.current = timed(NOW + 10_000, [[1, 10], [2, 8]], [[8, 50]])
    await tick()
    expect(onArrivals).toHaveBeenCalledTimes(1)
    // 그다음은 평소대로
    server.current = timed(NOW + 15_000, [[1, 15], [2, 13]], [[8, 55], [9, 30]])
    await tick()
    expect(onArrivals).toHaveBeenCalledTimes(2)
    expect(onArrivals).toHaveBeenLastCalledWith({ newPendingOrders: 0, newCalls: 1 })
    watcher.stop()
  })

  it('따라잡기 조회가 실패하면 따라잡기는 끝난다 — 복구된 다음 조회는 평소대로 알린다', async () => {
    const { server, fetchSnapshot, onArrivals, watcher, resume } = await startWithResume(timed(NOW, [[1, 0]]))
    fetchSnapshot.mockRejectedValueOnce(new Error('network'))
    resume('visible')
    await tick(0)
    server.current = timed(NOW + 90_000, [[1, 90], [2, 60]])
    await tick()
    expect(onArrivals).toHaveBeenCalledWith({ newPendingOrders: 1, newCalls: 0 })
    watcher.stop()
  })
})
