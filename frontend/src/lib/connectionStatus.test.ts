import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { isServerUnreachable, markReachable, subscribeConnection } from './connectionStatus'
import { fetchWithTimeout } from './fetchWithTimeout'
import { onResume } from './onResume'

beforeEach(() => markReachable())
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('끊김 배너 상태 — fetchWithTimeout이 기록', () => {
  it('네트워크 오류면 끊김, 다음 응답이 오면 복구', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    await fetchWithTimeout('/x').catch(() => {})
    expect(isServerUnreachable()).toBe(true)
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 200 })))
    await fetchWithTimeout('/x')
    expect(isServerUnreachable()).toBe(false)
  })

  it('시간 초과도 끊김', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_i: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_r, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })))
    const p = fetchWithTimeout('/x').catch(() => {})
    await vi.advanceTimersByTimeAsync(10_000)
    await p
    expect(isServerUnreachable()).toBe(true)
  })

  it('502·504(nginx는 떴는데 api가 안 닿음)는 끊김, 4xx·500·503(api가 낸 UPLOAD_BUSY)은 서버와 닿은 것', async () => {
    for (const [status, expected] of [[502, true], [503, false], [504, true], [409, false], [500, false], [401, false]] as const) {
      markReachable()
      vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status })))
      await fetchWithTimeout('/x')
      expect(isServerUnreachable(), `status ${status}`).toBe(expected)
    }
  })

  it('호출자가 취소한 요청(화면 이동)은 끊김으로 보지 않는다', async () => {
    const outer = new AbortController()
    vi.stubGlobal('fetch', vi.fn((_i: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_r, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })))
    const p = fetchWithTimeout('/x', { signal: outer.signal }).catch(() => {})
    outer.abort()
    await p
    expect(isServerUnreachable()).toBe(false)
  })

  it('상태가 바뀔 때만 구독자에게 알린다(같은 상태 반복은 다시 그리지 않음)', async () => {
    const listener = vi.fn()
    const off = subscribeConnection(listener)
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('x') }))
    await fetchWithTimeout('/x').catch(() => {})
    await fetchWithTimeout('/x').catch(() => {})
    expect(listener).toHaveBeenCalledTimes(1)
    off()
  })
})

describe('onResume — 폰을 다시 켜거나 인터넷이 돌아오면 바로 재조회', () => {
  function fakeDom() {
    const doc = new EventTarget() as EventTarget & { visibilityState: string }
    doc.visibilityState = 'visible'
    const win = new EventTarget()
    vi.stubGlobal('document', doc)
    vi.stubGlobal('window', win)
    return { doc, win }
  }

  it('화면이 보이게 되면 부른다, 숨겨질 때는 안 부른다', () => {
    const { doc } = fakeDom()
    const cb = vi.fn()
    onResume(cb)
    doc.visibilityState = 'hidden'
    doc.dispatchEvent(new Event('visibilitychange'))
    expect(cb).not.toHaveBeenCalled()
    doc.visibilityState = 'visible'
    doc.dispatchEvent(new Event('visibilitychange'))
    expect(cb).toHaveBeenCalledTimes(1)
  })

  it('online 이벤트에 부르고, 정리하면 더는 안 부른다', () => {
    const { win, doc } = fakeDom()
    const cb = vi.fn()
    const off = onResume(cb)
    win.dispatchEvent(new Event('online'))
    expect(cb).toHaveBeenCalledTimes(1)
    off()
    win.dispatchEvent(new Event('online'))
    doc.dispatchEvent(new Event('visibilitychange'))
    expect(cb).toHaveBeenCalledTimes(1)
  })
})
