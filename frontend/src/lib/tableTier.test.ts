import { describe, expect, it } from 'vitest'
import { tableTierOf } from './tableTier'

describe('tableTierOf — 테이블 카드 배색 2단계', () => {
  it('빈 테이블은 empty', () => {
    expect(tableTierOf({ status: 'EMPTY' })).toBe('empty')
  })

  it('손님이 있으면 주문 여부·경과시간과 무관하게 occupied', () => {
    expect(tableTierOf({ status: 'OCCUPIED' })).toBe('occupied')
  })
})
