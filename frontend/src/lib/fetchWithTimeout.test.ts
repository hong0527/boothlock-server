import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defaultTimeoutMs, fetchWithTimeout, TimeoutError } from './fetchWithTimeout'

/** 약한 신호에서 영영 안 끝나는 요청 — signal이 abort될 때만 거절된다 */
function hangingFetch() {
  return vi.fn((_input: RequestInfo | URL, init?: RequestInit) =>
    new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    }),
  )
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('fetchWithTimeout — 현장 인터넷이 약할 때', () => {
  it('조회가 10초 안에 안 끝나면 TimeoutError로 끊는다(화면이 옛 목록에 굳지 않게)', async () => {
    vi.stubGlobal('fetch', hangingFetch())
    const p = fetchWithTimeout('/api/v1/admin/orders')
    const caught = p.catch((e) => e)
    await vi.advanceTimersByTimeAsync(9_999)
    await vi.advanceTimersByTimeAsync(1)
    expect(await caught).toBeInstanceOf(TimeoutError)
  })

  it('9.9초에는 아직 기다린다', async () => {
    vi.stubGlobal('fetch', hangingFetch())
    let settled = false
    fetchWithTimeout('/x').catch(() => {}).finally(() => { settled = true })
    await vi.advanceTimersByTimeAsync(9_900)
    expect(settled).toBe(false)
    await vi.advanceTimersByTimeAsync(200)
    expect(settled).toBe(true)
  })

  it('빨리 오면 그대로 응답을 준다', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('ok', { status: 200 })))
    const res = await fetchWithTimeout('/x')
    expect(res.status).toBe(200)
  })

  it('호출자가 먼저 취소하면 TimeoutError가 아니라 원래 취소 예외다', async () => {
    vi.stubGlobal('fetch', hangingFetch())
    const outer = new AbortController()
    const caught = fetchWithTimeout('/x', { signal: outer.signal }).catch((e) => e)
    outer.abort()
    const err = await caught
    expect(err).not.toBeInstanceOf(TimeoutError)
    expect((err as Error).name).toBe('AbortError')
  })

  it('기본 제한 시간: 조회 10초, 변경 15초, 사진 업로드 60초', () => {
    expect(defaultTimeoutMs({})).toBe(10_000)
    expect(defaultTimeoutMs({ method: 'post' })).toBe(15_000)
    expect(defaultTimeoutMs({ method: 'PATCH' })).toBe(15_000)
    expect(defaultTimeoutMs({ method: 'POST', body: new FormData() })).toBe(60_000)
  })
})
