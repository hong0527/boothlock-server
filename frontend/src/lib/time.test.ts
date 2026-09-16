import { describe, expect, it } from 'vitest'
import { businessDateKst } from './time'

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
