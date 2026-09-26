import { describe, expect, it } from 'vitest'
import { businessDateKst, formatElapsedClock } from './time'

describe('formatElapsedClock — 테이블 카드 경과시간(시:분) 표시', () => {
  it('35분 경과는 "0:35"', () => {
    const created = '2026-09-16T18:00:00+09:00'
    const now = new Date('2026-09-16T18:35:00+09:00').getTime()
    expect(formatElapsedClock(created, now)).toBe('0:35')
  })

  it('2시간 35분 경과는 "2:35"', () => {
    const created = '2026-09-16T18:00:00+09:00'
    const now = new Date('2026-09-16T20:35:00+09:00').getTime()
    expect(formatElapsedClock(created, now)).toBe('2:35')
  })

  it('분은 항상 두 자리로 채운다', () => {
    const created = '2026-09-16T18:00:00+09:00'
    const now = new Date('2026-09-16T18:05:00+09:00').getTime()
    expect(formatElapsedClock(created, now)).toBe('0:05')
  })

  it('음수 경과(시계 오차 등)는 0으로 바닥 처리한다', () => {
    const created = '2026-09-16T18:00:00+09:00'
    const now = new Date('2026-09-16T17:59:00+09:00').getTime()
    expect(formatElapsedClock(created, now)).toBe('0:00')
  })
})

describe('businessDateKst — 06:00 KST 영업일 경계 (백엔드 businessDateOf와 동일)', () => {
  it('05:59:59 KST는 전날 영업일', () => {
    expect(businessDateKst(new Date('2026-09-16T05:59:59+09:00'))).toBe('2026-09-15')
  })

  it('06:00:00 KST부터 당일 영업일', () => {
    expect(businessDateKst(new Date('2026-09-16T06:00:00+09:00'))).toBe('2026-09-16')
  })

  it('자정 직후(00:30 KST)는 전날 영업일 — 새벽 운영 중 대시보드가 비지 않게', () => {
    expect(businessDateKst(new Date('2026-09-16T00:30:00+09:00'))).toBe('2026-09-15')
  })

  it('낮 시간은 달력 날짜와 같다', () => {
    expect(businessDateKst(new Date('2026-09-16T14:00:00+09:00'))).toBe('2026-09-16')
  })

  it('실행 환경 시간대와 무관하게 KST 기준으로 계산한다 (UTC 입력)', () => {
    // 2026-09-15T20:30Z = 09-16 05:30 KST → 전날 영업일
    expect(businessDateKst(new Date('2026-09-15T20:30:00Z'))).toBe('2026-09-15')
    // 2026-09-15T21:00Z = 09-16 06:00 KST → 당일
    expect(businessDateKst(new Date('2026-09-15T21:00:00Z'))).toBe('2026-09-16')
  })
})
