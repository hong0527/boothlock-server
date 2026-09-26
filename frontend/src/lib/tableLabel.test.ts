import { describe, expect, it } from 'vitest'
import { tableNumberLabel } from './tableLabel'

describe('tableNumberLabel — 테이블 카드에 숫자만 표시 (Figma 672:1247)', () => {
  it('"T-24" → "24"', () => {
    expect(tableNumberLabel('T-24')).toBe('24')
  })

  it('"T-N" 형태가 아니면 원래 값을 그대로 보여준다', () => {
    expect(tableNumberLabel('수기-1')).toBe('수기-1')
  })
})
