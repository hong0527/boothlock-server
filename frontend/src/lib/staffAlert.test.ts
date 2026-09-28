import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// 화면 꺼짐 방지(wakeLock) 잠금이 새지 않는가 — 모듈 상태를 테스트마다 새로 받는다
type Sentinel = { release: ReturnType<typeof vi.fn>; addEventListener: () => void }

let requests: { resolve: (s: Sentinel) => void }[]
let sentinels: Sentinel[]

async function load() {
  vi.resetModules()
  return import('./staffAlert')
}

beforeEach(() => {
  requests = []
  sentinels = []
  vi.stubGlobal('document', { visibilityState: 'visible' })
  vi.stubGlobal('navigator', {
    wakeLock: {
      request: () => new Promise<Sentinel>((resolve) => {
        requests.push({ resolve })
      }),
    },
  })
})
afterEach(() => { vi.unstubAllGlobals() })

function grant(index: number) {
  const sentinel: Sentinel = { release: vi.fn(() => Promise.resolve()), addEventListener: () => {} }
  sentinels.push(sentinel)
  requests[index].resolve(sentinel)
}

describe('acquireWakeLock', () => {
  it('요청 중에 풀면(알림 끔·화면 이탈) 늦게 받은 잠금을 바로 돌려준다', async () => {
    const { acquireWakeLock, releaseWakeLock } = await load()
    const pending = acquireWakeLock()
    releaseWakeLock()
    grant(0)
    await pending
    expect(sentinels[0].release).toHaveBeenCalledTimes(1)
  })

  it('요청 중에 또 잡으면 요청을 겹쳐 보내지 않는다', async () => {
    const { acquireWakeLock } = await load()
    const first = acquireWakeLock()
    const second = acquireWakeLock()
    expect(requests).toHaveLength(1)
    grant(0)
    await Promise.all([first, second])
  })

  it('정상으로 잡은 잠금은 releaseWakeLock에서 푼다', async () => {
    const { acquireWakeLock, releaseWakeLock } = await load()
    const pending = acquireWakeLock()
    grant(0)
    await pending
    expect(sentinels[0].release).not.toHaveBeenCalled()
    releaseWakeLock()
    expect(sentinels[0].release).toHaveBeenCalledTimes(1)
  })
})

// 알림음 — 실제 소리 대신 "무엇을 언제 예약했는가"만 보는 최소 가짜 AudioContext
type FakeOsc = { type: string; startAt: number; stopAt: number; disconnect: ReturnType<typeof vi.fn> }

function fakeAudio(state: 'running' | 'suspended') {
  const oscillators: FakeOsc[] = []
  const passThrough = (node: unknown) => node
  const ctx = {
    state: state as string,
    currentTime: 0,
    destination: {},
    // 사용자 동작 밖의 resume()처럼 기본은 아무것도 풀지 않는다
    resume: vi.fn(() => Promise.resolve()),
    createGain: () => ({ gain: { setValueAtTime() {}, exponentialRampToValueAtTime() {} }, connect: passThrough, disconnect() {} }),
    createBiquadFilter: () => ({ type: '', frequency: { value: 0 }, connect: passThrough }),
    createOscillator: () => {
      const osc = {
        type: '', frequency: { value: 0 }, onended: null, startAt: -1, stopAt: -1,
        connect: passThrough, disconnect: vi.fn(),
        start(at: number) { osc.startAt = at },
        stop(at?: number) { osc.stopAt = at ?? ctx.currentTime },
      }
      oscillators.push(osc)
      return osc
    },
  }
  vi.stubGlobal('window', { AudioContext: function FakeAudioContext() { return ctx } })
  return { ctx, oscillators }
}

describe('알림음', () => {
  it('오디오가 잠겨(suspended) 있으면 폴링 알림음을 예약하지 않는다 — 쌓였다가 화면을 누를 때 한꺼번에 터지지 않게', async () => {
    const { oscillators } = fakeAudio('suspended')
    const { playCallAlert, playOrderAlert } = await load()
    // 새로고침 뒤 화면을 만지기 전 — 컨텍스트가 없어 폴링이 직접 만들어도 suspended다
    playOrderAlert()
    playCallAlert()
    playOrderAlert()
    expect(oscillators).toHaveLength(0)
  })

  it('주문·호출이 한 번에 오면 주문음이 끝난 뒤에 호출음을 잇고, 음색도 다르다', async () => {
    const { oscillators } = fakeAudio('running')
    const { playCallAlert, playOrderAlert } = await load()
    playOrderAlert()
    playCallAlert()
    const order = oscillators.filter(o => o.type === 'sine')
    const call = oscillators.filter(o => o.type === 'square')
    expect(order.length).toBeGreaterThan(0)
    expect(call.length).toBeGreaterThan(0)
    expect(Math.min(...call.map(o => o.startAt))).toBeGreaterThanOrEqual(Math.max(...order.map(o => o.stopAt)))
  })

  it('stopAlertSounds는 예약된 소리를 모두 끊고, 다음 소리는 기다리지 않고 바로 낸다', async () => {
    const { ctx, oscillators } = fakeAudio('running')
    const { playCallAlert, playOrderAlert, stopAlertSounds } = await load()
    playOrderAlert()
    playCallAlert()
    stopAlertSounds()
    expect(oscillators.every(o => o.disconnect.mock.calls.length > 0)).toBe(true)
    // 끊은 소리 뒤로 줄 서지 않는다
    oscillators.length = 0
    playCallAlert()
    expect(Math.min(...oscillators.map(o => o.startAt))).toBe(ctx.currentTime)
  })

  it('알림을 켤 때 확인음은 resume()이 끝난 뒤 울리고, 그 사이 끄면 울리지 않는다', async () => {
    const { ctx, oscillators } = fakeAudio('suspended')
    ctx.resume.mockImplementation(async () => { ctx.state = 'running' })
    const { previewOrderAlert, stopAlertSounds, unlockAudio } = await load()
    unlockAudio()
    previewOrderAlert()
    await vi.waitFor(() => expect(oscillators.length).toBeGreaterThan(0))

    oscillators.length = 0
    ctx.state = 'suspended'
    previewOrderAlert()
    stopAlertSounds()
    // resume()과 그 뒤 then까지 모두 끝나게 한 매크로태스크 기다린다
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(ctx.state).toBe('running')
    expect(oscillators).toHaveLength(0)
  })
})
