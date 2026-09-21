import { describe, expect, it } from 'vitest'
import { createPollGuard } from './pollGuard'

describe('겹침 방지', () => {
  it('요청 중이면 폴링 주기를 건너뛴다', () => {
    const guard = createPollGuard()
    expect(guard.begin()).not.toBeNull()
    expect(guard.begin(true)).toBeNull()
  })

  it('끝나면 다음 주기는 다시 돈다', () => {
    const guard = createPollGuard()
    const first = guard.begin()
    expect(guard.begin(true)).toBeNull()
    expect(first).not.toBeNull()
    guard.end()
    expect(guard.begin(true)).not.toBeNull()
  })

  it('skipIfBusy가 아니면 요청 중이어도 통과한다 — 액션 직후 즉시 갱신용', () => {
    const guard = createPollGuard()
    guard.begin()
    expect(guard.begin(false)).not.toBeNull()
  })

  it('여러 개가 겹쳤다면 전부 끝나야 다시 돈다', () => {
    const guard = createPollGuard()
    guard.begin()
    guard.begin()
    guard.end()
    expect(guard.begin(true)).toBeNull()
    guard.end()
    expect(guard.begin(true)).not.toBeNull()
  })
})

describe('응답 역전 방지', () => {
  it('나중에 시작된 요청이 있으면 옛 응답은 최신이 아니다', () => {
    const guard = createPollGuard()
    const older = guard.begin() as number
    const newer = guard.begin() as number

    expect(guard.isLatest(older)).toBe(false)
    expect(guard.isLatest(newer)).toBe(true)
  })

  it('늦게 온 옛 응답이 끝나도 최신 판정은 그대로다 — 옛 데이터가 화면을 덮지 않는다', () => {
    const guard = createPollGuard()
    const older = guard.begin() as number
    const newer = guard.begin() as number

    guard.end() // 옛 요청이 나중에 도착해 끝남
    expect(guard.isLatest(older)).toBe(false)
    expect(guard.isLatest(newer)).toBe(true)
  })

  it('혼자 도는 요청은 최신이다', () => {
    const guard = createPollGuard()
    const only = guard.begin() as number
    expect(guard.isLatest(only)).toBe(true)
  })

  it('건너뛴 주기는 run id를 소비하지 않는다', () => {
    const guard = createPollGuard()
    const first = guard.begin() as number
    expect(guard.begin(true)).toBeNull()
    expect(guard.isLatest(first)).toBe(true)
  })
})
