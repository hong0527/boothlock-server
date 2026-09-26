import { describe, expect, it } from 'vitest'
import { tableTierOf } from './tableTier'

describe('tableTierOf — 테이블 카드 배색 3단계', () => {
  const now = Date.now()

  it('EMPTY면 항상 empty (주문이 있어도 — 이론상 안 나오지만 방어적으로)', () => {
    expect(tableTierOf({ status: 'EMPTY', firstOrderAt: null }, now)).toBe('empty')
  })

  it('OCCUPIED인데 아직 주문이 없으면 seated(테두리만)', () => {
    expect(tableTierOf({ status: 'OCCUPIED', firstOrderAt: null }, now)).toBe('seated')
  })

  it('OCCUPIED + 2시간 이내 경과면 seated(테두리만)', () => {
    const firstOrderAt = new Date(now - 60 * 60 * 1000).toISOString() // 1시간 전
    expect(tableTierOf({ status: 'OCCUPIED', firstOrderAt }, now)).toBe('seated')
  })

  it('OCCUPIED + 2시간 초과 경과면 longWait(채움)', () => {
    const firstOrderAt = new Date(now - 150 * 60 * 1000).toISOString() // 2시간30분 전
    expect(tableTierOf({ status: 'OCCUPIED', firstOrderAt }, now)).toBe('longWait')
  })
})
