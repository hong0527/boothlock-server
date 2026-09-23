import { describe, expect, it } from 'vitest'
import { isLongWait } from './time'

// 서버가 실제로 주는 형식(+09:00 오프셋)으로 검증한다 — 기기 시간대와 무관해야 한다
describe('테이블 카드 2시간 초과 판정(isLongWait)', () => {
  const first = '2026-09-23T20:00:00.123456+09:00'
  const at = (s: string) => Date.parse(s)

  it('첫 주문이 없으면 false', () => {
    expect(isLongWait(null, Date.now())).toBe(false)
  })
  it('1시간 59분이면 false', () => {
    expect(isLongWait(first, at('2026-09-23T21:59:00.123+09:00'))).toBe(false)
  })
  it('정확히 2시간이면 아직 false(초과가 기준)', () => {
    expect(isLongWait(first, at('2026-09-23T22:00:00.123+09:00'))).toBe(false)
  })
  it('2시간을 1초 넘기면 true', () => {
    expect(isLongWait(first, at('2026-09-23T22:00:01.123+09:00'))).toBe(true)
  })
  it('자정을 넘겨도 맞다(영업일 경계와 무관한 절대시각 차이)', () => {
    expect(isLongWait('2026-09-23T23:30:00+09:00', at('2026-09-24T01:30:01+09:00'))).toBe(true)
  })
  it('잘못된 값이면 빨강으로 만들지 않는다', () => {
    expect(isLongWait('garbage', Date.now())).toBe(false)
  })
})
