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
