import { beforeEach, expect, it, vi } from 'vitest'
import { apiFetch } from './apiFetch'
import { moveTable } from './orderActions'

vi.mock('./apiFetch', () => ({ apiFetch: vi.fn() }))

beforeEach(() => vi.clearAllMocks())

it('화면에서 확인한 출발 세션을 이동 요청에 고정하고 409 응답을 호출자에게 전달한다', async () => {
  const rejected = new Response(null, { status: 409 })
  vi.mocked(apiFetch).mockResolvedValue(rejected)

  expect(await moveTable(1, 2, 101)).toBe(rejected)
  expect(apiFetch).toHaveBeenCalledWith('/api/v1/admin/tables/1/move', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ toTableId: 2, sessionId: 101 }),
  })
  expect(apiFetch).toHaveBeenCalledTimes(1)
})
