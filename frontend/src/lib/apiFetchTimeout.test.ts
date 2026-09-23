import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TimeoutError } from './fetchWithTimeout'

const store = new Map<string, string>()
const storage = {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => { store.set(k, v) },
  removeItem: (k: string) => { store.delete(k) },
  clear: () => store.clear(),
}

beforeEach(() => {
  vi.useFakeTimers()
  store.clear()
  vi.stubGlobal('localStorage', storage)
  vi.stubGlobal('window', { location: { href: '' }, localStorage: storage })
  vi.stubGlobal('fetch', vi.fn((_i: RequestInfo | URL, init?: RequestInit) =>
    new Promise<Response>((_r, reject) => init?.signal?.addEventListener('abort', () => reject(new DOMException('a', 'AbortError'))))))
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

describe('공통 요청 함수에 제한 시간이 붙어 있다', () => {
  it('운영자 apiFetch — 매달린 요청이 10초에 끊긴다', async () => {
    store.set('boothlock_token', 'tok')
    const { apiFetch } = await import('./apiFetch')
    const caught = apiFetch('/api/v1/admin/orders').catch((e) => e)
    await vi.advanceTimersByTimeAsync(10_000)
    expect(await caught).toBeInstanceOf(TimeoutError)
  })

  it('손님 customerApiFetch — 매달린 요청이 10초에 끊긴다', async () => {
    store.set('boothlock_session_token', 'st')
    const { customerApiFetch } = await import('./customerApiFetch')
    const caught = customerApiFetch('/api/v1/orders').catch((e) => e)
    await vi.advanceTimersByTimeAsync(10_000)
    expect(await caught).toBeInstanceOf(TimeoutError)
  })
})
