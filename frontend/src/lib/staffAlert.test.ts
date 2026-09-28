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
type FakeOsc = {
  type: string; frequency: { value: number }; startAt: number; stopAt: number; disconnect: ReturnType<typeof vi.fn>
}
/** 게인 노드 — 마지막으로 정해진 값만 기억한다(마스터 게인 = 알림음 크기 확인용) */
type FakeGain = { gain: { value: number } }

function fakeAudio(state: 'running' | 'suspended') {
  const oscillators: FakeOsc[] = []
  const gains: FakeGain[] = []
  const passThrough = (node: unknown) => node
  const param = () => ({ value: 0 })
  const ctx = {
    state: state as string,
    currentTime: 0,
    destination: {},
    // 사용자 동작 밖의 resume()처럼 기본은 아무것도 풀지 않는다
    resume: vi.fn(() => Promise.resolve()),
    suspend: vi.fn(() => Promise.resolve()),
    createGain: () => {
      const node = {
        gain: {
          value: 1,
          setValueAtTime(v: number) { node.gain.value = v },
          setTargetAtTime(v: number) { node.gain.value = v },
          exponentialRampToValueAtTime() {},
        },
        connect: passThrough, disconnect() {},
      }
      gains.push(node)
      return node
    },
    createDynamicsCompressor: () => ({
      threshold: param(), knee: param(), ratio: param(), attack: param(), release: param(), connect: passThrough,
    }),
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
  return { ctx, oscillators, gains }
}

/** 새로고침을 흉내내는 localStorage — 모듈을 다시 불러와도(load) 값이 남는다 */
function fakeLocalStorage() {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => { store.set(k, v) },
    removeItem: (k: string) => { store.delete(k) },
  })
  return store
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

  it('주문음은 이어지며 점점 높아지는 네 음 "띠디리링↗" — 마지막 음을 가장 길게, 전체 약 1.8초. 호출음은 내려가는 사각파라 음높이 방향부터 다르다', async () => {
    const { oscillators } = fakeAudio('running')
    const { playCallAlert, playOrderAlert } = await load()
    playOrderAlert()
    // 기음만(배음 제외) 시작 순서대로 — 배음은 기음의 2·3배라 가장 낮은 것만 남긴다
    const fundamentals = (list: FakeOsc[]) => {
      const byStart = new Map<number, number>()
      for (const o of list) byStart.set(o.startAt, Math.min(byStart.get(o.startAt) ?? Infinity, o.frequency.value))
      return [...byStart.entries()].sort((a, b) => a[0] - b[0]).map(([, f]) => f)
    }
    const order = oscillators.filter(o => o.type === 'sine')
    const orderNotes = fundamentals(order)
    expect(orderNotes).toHaveLength(4)
    // 띠 → 디 → 리 → 링: 한 음씩 계속 올라간다
    for (let i = 1; i < orderNotes.length; i++) expect(orderNotes[i]).toBeGreaterThan(orderNotes[i - 1])
    // 음마다 기음이 가장 오래 운다 — 시작 순서대로 [시작, 끝]
    const notes = [...new Set(order.map(o => o.startAt))].sort((a, b) => a - b)
      .map(at => ({ at, end: Math.max(...order.filter(o => o.startAt === at).map(o => o.stopAt)) }))
    // 끊기지 않고 이어진다 — 다음 음이 앞 음이 다 사라지기 전에 시작한다
    for (let i = 1; i < notes.length; i++) expect(notes[i].at).toBeLessThan(notes[i - 1].end)
    // 마지막 "링"이 가장 길게 운다
    const lengths = notes.map(n => n.end - n.at)
    expect(Math.max(...lengths.slice(0, -1))).toBeLessThan(lengths.at(-1)!)
    // 전체 약 1.8초
    const total = Math.max(...order.map(o => o.stopAt)) - notes[0].at
    expect(total).toBeGreaterThan(1.6)
    expect(total).toBeLessThan(2)

    oscillators.length = 0
    playCallAlert()
    const callNotes = fundamentals(oscillators.filter(o => o.type === 'square'))
    expect(callNotes[1]).toBeLessThan(callNotes[0])
    expect(Math.max(...callNotes)).toBeLessThan(Math.min(...orderNotes))
  })
})

describe('알림음 크기', () => {
  it('기본값은 70%이고, 그때 마스터 게인은 1(예전 크기)', async () => {
    const { gains } = fakeAudio('running')
    const { DEFAULT_ALERT_VOLUME, getAlertVolume, playOrderAlert } = await load()
    expect(getAlertVolume()).toBe(70)
    expect(DEFAULT_ALERT_VOLUME).toBe(70)
    playOrderAlert()
    // 처음 만든 게인이 마스터(출구)다 — 그 뒤 것들은 음 하나하나의 봉투
    expect(gains[0].gain.value).toBeCloseTo(1)
  })

  it('크기를 바꾸면 다음 알림음부터 반영되고, 주문음·호출음이 같은 크기를 탄다', async () => {
    const { gains } = fakeAudio('running')
    const { playCallAlert, playOrderAlert, setAlertVolume } = await load()
    playOrderAlert()
    const master = gains[0]
    setAlertVolume(35)
    expect(master.gain.value).toBeCloseTo(Math.pow(35 / 70, 1.5))
    playCallAlert()
    expect(master.gain.value).toBeCloseTo(Math.pow(35 / 70, 1.5))
    setAlertVolume(0)
    playOrderAlert()
    expect(master.gain.value).toBe(0)
  })

  it('크기는 0~100으로 자르고 저장해 새로고침 뒤에도 남는다 — 알림을 꺼도 그대로', async () => {
    fakeLocalStorage()
    fakeAudio('running')
    const first = await load()
    first.setAlertVolume(150)
    expect(first.getAlertVolume()).toBe(100)
    first.setAlertVolume(-5)
    expect(first.getAlertVolume()).toBe(0)
    first.setAlertVolume(40)
    first.turnAlertsOff()
    const reloaded = await load()
    expect(reloaded.getAlertVolume()).toBe(40)
    expect(reloaded.isAlertPreferred()).toBe(false)
  })

  it('저장값이 망가져 있으면 기본값', async () => {
    const store = fakeLocalStorage()
    store.set('boothlock_staff_alert_volume', 'abc')
    const { getAlertVolume } = await load()
    expect(getAlertVolume()).toBe(70)
  })
})

describe('알림 켜고 끔 — 설정 화면과 주문현황이 같은 값 하나를 쓴다', () => {
  it('turnAlertsOn은 오디오를 풀고 확인음을 내며 켜짐으로 저장, turnAlertsOff는 소리를 끊고 재우며 꺼짐으로 저장', async () => {
    const { ctx, oscillators } = fakeAudio('running')
    const { isAlertPreferred, turnAlertsOff, turnAlertsOn } = await load()
    turnAlertsOn()
    expect(isAlertPreferred()).toBe(true)
    // 확인음이 실제 음원을 잠깐 기다렸다가 나갈 수 있어(playWhenReady) 한 틱 늦게 예약된다
    await vi.waitFor(() => expect(oscillators.length).toBeGreaterThan(0))
    turnAlertsOff()
    expect(isAlertPreferred()).toBe(false)
    expect(oscillators.every(o => o.disconnect.mock.calls.length > 0)).toBe(true)
    expect(ctx.suspend).toHaveBeenCalledTimes(1)
  })

  it('한 화면에서 바꾸면 구독한 다른 화면이 바로 안다 — 해제하면 더는 안 알린다', async () => {
    fakeAudio('running')
    const { subscribeAlertPreference, turnAlertsOff, turnAlertsOn } = await load()
    const seen: boolean[] = []
    const stop = subscribeAlertPreference(on => seen.push(on))
    turnAlertsOn()
    turnAlertsOff()
    stop()
    turnAlertsOn()
    expect(seen).toEqual([true, false])
  })

  it('다른 탭에서 바꾸면 storage 이벤트로 안다(알림 키만)', async () => {
    const listeners: ((e: { key: string; newValue: string | null }) => void)[] = []
    vi.stubGlobal('window', { addEventListener: (_t: string, l: never) => listeners.push(l), removeEventListener() {} })
    const { subscribeAlertPreference } = await load()
    const seen: boolean[] = []
    subscribeAlertPreference(on => seen.push(on))
    listeners.forEach(l => l({ key: 'boothlock_staff_alert_volume', newValue: '10' }))
    listeners.forEach(l => l({ key: 'boothlock_staff_alert', newValue: 'off' }))
    listeners.forEach(l => l({ key: 'boothlock_staff_alert', newValue: 'on' }))
    expect(seen).toEqual([false, true])
  })
})
