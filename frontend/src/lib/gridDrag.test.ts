import { describe, expect, it } from 'vitest'
import { clampIndex, nextFreeCell, pixelToGridIndex, resolveDrop } from './gridDrag'

describe('clampIndex', () => {
  it('범위 안이면 그대로', () => expect(clampIndex(5, 1, 50)).toBe(5))
  it('최소보다 작으면 최소로', () => expect(clampIndex(-3, 1, 50)).toBe(1))
  it('최대보다 크면 최대로', () => expect(clampIndex(99, 1, 50)).toBe(50))
})

describe('pixelToGridIndex — 픽셀을 가장 가까운 칸으로 스냅', () => {
  it('0px는 1번째 칸(min)', () => expect(pixelToGridIndex(0, 1, 50)).toBe(1))
  it('정확히 한 칸(112px) 이동하면 다음 칸', () => expect(pixelToGridIndex(112, 1, 50)).toBe(2))
  it('반 칸 이내면 가까운 칸으로 반올림', () => expect(pixelToGridIndex(50, 1, 50)).toBe(1))
  it('음수 픽셀(왼쪽 밖으로 드래그)은 최소 칸으로 고정', () => expect(pixelToGridIndex(-500, 1, 50)).toBe(1))
})

describe('resolveDrop — 드롭 대상 칸이 비어있는지/차 있는지에 따른 변경 목록', () => {
  it('빈 칸으로 드롭하면 자신만 옮긴다', () => {
    const changes = resolveDrop(1, { row: 1, col: 1 }, { row: 2, col: 3 }, [{ tableId: 1, row: 1, col: 1 }])
    expect(changes).toEqual([{ tableId: 1, row: 2, col: 3 }])
  })

  it('다른 테이블이 있는 칸으로 드롭하면 서로 자리를 맞바꾼다', () => {
    const placed = [
      { tableId: 1, row: 1, col: 1 },
      { tableId: 2, row: 2, col: 3 },
    ]
    const changes = resolveDrop(1, { row: 1, col: 1 }, { row: 2, col: 3 }, placed)
    expect(changes).toEqual(
      expect.arrayContaining([
        { tableId: 1, row: 2, col: 3 },
        { tableId: 2, row: 1, col: 1 },
      ]),
    )
    expect(changes).toHaveLength(2)
  })

  it('미배치 트레이(origin null)에서 빈 칸으로 드롭', () => {
    const changes = resolveDrop(5, { row: null, col: null }, { row: 4, col: 4 }, [])
    expect(changes).toEqual([{ tableId: 5, row: 4, col: 4 }])
  })

  it('미배치 트레이에서 이미 있는 칸으로 드롭하면 원래 테이블은 미배치로 밀려난다', () => {
    const placed = [{ tableId: 1, row: 4, col: 4 }]
    const changes = resolveDrop(5, { row: null, col: null }, { row: 4, col: 4 }, placed)
    expect(changes).toEqual(
      expect.arrayContaining([
        { tableId: 5, row: 4, col: 4 },
        { tableId: 1, row: null, col: null },
      ]),
    )
  })

  it('같은 칸에 도로 놓으면(움직이지 않음) 변경 없음', () => {
    expect(resolveDrop(1, { row: 2, col: 2 }, { row: 2, col: 2 }, [{ tableId: 1, row: 2, col: 2 }])).toEqual([])
  })
})

describe('nextFreeCell — "테이블 추가" 시 자동 배치할 다음 빈 칸', () => {
  it('아무것도 없으면 (1,1)', () => {
    expect(nextFreeCell([], 10, 50)).toEqual({ row: 1, col: 1 })
  })

  it('한 행이 다 차면 다음 행 첫 칸으로', () => {
    const placed = Array.from({ length: 10 }, (_, i) => ({ row: 1, col: i + 1 }))
    expect(nextFreeCell(placed, 10, 50)).toEqual({ row: 2, col: 1 })
  })

  it('중간에 빈 칸이 있으면(삭제 등) 그 칸을 먼저 채운다', () => {
    const placed = [
      { row: 1, col: 1 },
      { row: 1, col: 3 },
    ]
    expect(nextFreeCell(placed, 10, 50)).toEqual({ row: 1, col: 2 })
  })
})
