import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ArrivalSnapshot, Arrivals } from './newArrivals'

// 공통 알림 감시 — 모듈 상태(동시에 도는 감시 하나)를 테스트마다 새로 받는다
async function load() {
  vi.resetModules()
  return import('./staffAlertWatcher')
}

const snap = (orders: number[], calls: number[] = []): ArrivalSnapshot => ({
  pendingOrderIds: new Set(orders),
  callIds: new Set(calls),
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
    let resume: (() => void) | null = null
    const offResume = vi.fn()
    const onArrivals = vi.fn()
    const watcher = createArrivalWatcher({
      fetchSnapshot, onArrivals,
      onResume: (listener) => { resume = listener; return offResume },
    })
    watcher.start()
    await tick(0)
    server.current = snap([1, 2])
    resume!()
    await tick(0)
    expect(onArrivals).toHaveBeenCalledTimes(1)
    watcher.stop()
    expect(offResume).toHaveBeenCalledTimes(1)
  })
})
