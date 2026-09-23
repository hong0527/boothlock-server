import { describe, expect, it } from 'vitest'
import { isLongWait } from './TableGridCard'

describe('테이블 카드 경과시간 색상 판정(isLongWait)', () => {
  it('첫 주문이 없으면 false', () => {
    expect(isLongWait(null, Date.now())).toBe(false)
  })

  it('첫 주문 후 2시간 이내면 false', () => {
    const firstOrderAt = '2026-09-23T10:00:00'
    const now = Date.parse('2026-09-23T11:59:00') // 1시간 59분 후
    expect(isLongWait(firstOrderAt, now)).toBe(false)
  })

  it('정확히 2시간이면 아직 false(초과가 기준)', () => {
    const firstOrderAt = '2026-09-23T10:00:00'
    const now = Date.parse('2026-09-23T12:00:00')
    expect(isLongWait(firstOrderAt, now)).toBe(false)
  })

  it('2시간을 넘기면 true', () => {
    const firstOrderAt = '2026-09-23T10:00:00'
    const now = Date.parse('2026-09-23T12:00:01') // 2시간 1초 후
    expect(isLongWait(firstOrderAt, now)).toBe(true)
  })
})
