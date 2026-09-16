import { describe, expect, it } from 'vitest'
import { containRect, crowdLevel, pinPosition, seatDescription } from './eventMap'

describe('crowdLevel — 명세 E1 권장 임계값', () => {
  it('empty/total ≥ 0.5 → 여유', () => {
    expect(crowdLevel(10, 5)).toBe('여유')
    expect(crowdLevel(10, 10)).toBe('여유')
    expect(crowdLevel(6, 3)).toBe('여유')
  })

  it('0 < empty/total < 0.5 → 보통', () => {
    expect(crowdLevel(10, 4)).toBe('보통')
    expect(crowdLevel(10, 1)).toBe('보통')
  })

  it('empty = 0 → 만석', () => {
    expect(crowdLevel(10, 0)).toBe('만석')
  })

  it('테이블이 없는 부스(total 0)는 만석으로 표시 (앉을 자리가 없다는 뜻)', () => {
    expect(crowdLevel(0, 0)).toBe('만석')
  })
})

describe('seatDescription', () => {
  it('주문 마감이 좌석 수보다 우선', () => {
    expect(seatDescription(10, 5, false)).toBe('주문 마감')
  })
  it('남은 테이블 수', () => {
    expect(seatDescription(10, 3, true)).toBe('테이블 3개 남음')
    expect(seatDescription(10, 0, true)).toBe('남은 테이블 없음')
    expect(seatDescription(0, 0, true)).toBe('테이블 정보 없음')
  })
})

describe('containRect — object-fit: contain 영역', () => {
  it('가로가 남는 컨테이너: 높이를 맞추고 좌우 여백', () => {
    // 1600×1200 이미지를 375×337 에 contain → scale = min(375/1600, 337/1200) = 0.234375 (375/1600)
    // 실제로는 337/1200 = 0.2808 > 0.234375 이므로 폭 기준. width 375, height 281.25, top (337-281.25)/2
    const r = containRect(375, 337, 1600, 1200)
    expect(r.width).toBeCloseTo(375)
    expect(r.height).toBeCloseTo(281.25)
    expect(r.left).toBeCloseTo(0)
    expect(r.top).toBeCloseTo(27.875)
  })

  it('세로가 남는 컨테이너(접힘 상태 전체 화면): 폭을 맞추고 상하 여백', () => {
    const r = containRect(375, 812, 1600, 1200)
    expect(r.width).toBeCloseTo(375)
    expect(r.height).toBeCloseTo(281.25)
    expect(r.top).toBeCloseTo((812 - 281.25) / 2)
  })

  it('크기가 0이면 빈 영역 (측정 전 렌더에서 NaN 방지)', () => {
    expect(containRect(0, 0, 1600, 1200)).toEqual({ left: 0, top: 0, width: 0, height: 0 })
    expect(containRect(375, 337, 0, 0)).toEqual({ left: 0, top: 0, width: 0, height: 0 })
  })
})

describe('pinPosition — mapX/10000 * 표시폭 + 여백', () => {
  const rect = { left: 10, top: 20, width: 400, height: 300 }

  it('명세 예시 좌표 (3200, 5400)', () => {
    const p = pinPosition(3200, 5400, rect)
    expect(p.x).toBeCloseTo(10 + 0.32 * 400)
    expect(p.y).toBeCloseTo(20 + 0.54 * 300)
  })

  it('모서리', () => {
    expect(pinPosition(0, 0, rect)).toEqual({ x: 10, y: 20 })
    expect(pinPosition(10000, 10000, rect)).toEqual({ x: 410, y: 320 })
  })

  it('범위 밖 값은 가장자리로 고정 (잘못된 데이터로 핀이 화면 밖으로 나가지 않게)', () => {
    expect(pinPosition(-100, 20000, rect)).toEqual({ x: 10, y: 320 })
  })
})
