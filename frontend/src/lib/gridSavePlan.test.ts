import { describe, expect, it } from 'vitest'
import { planGridSave, type GridCell } from './gridSavePlan'

/** 서버처럼 한 칸에 한 테이블만 허용하며 순서대로 적용해 본다 — 실패하면 그 테이블 id를 돌려준다 */
function simulate(current: GridCell[], plan: GridCell[]): { failed: number[]; final: GridCell[] } {
  const state = new Map(current.map((c) => [c.tableId, { ...c }]))
  const failed: number[] = []
  for (const op of plan) {
    if (op.row != null && op.col != null) {
      const taken = [...state.values()].some((t) => t.tableId !== op.tableId && t.row === op.row && t.col === op.col)
      if (taken) { failed.push(op.tableId); continue }
    }
    state.set(op.tableId, { ...op })
  }
  return { failed, final: [...state.values()] }
}
const at = (s: GridCell[], id: number) => s.find((t) => t.tableId === id)

describe('그리드 저장 순서', () => {
  it('두 테이블 자리 맞바꾸기가 성공한다 — 예전엔 둘 다 409', () => {
    const cur = [{ tableId: 1, row: 1, col: 1 }, { tableId: 2, row: 1, col: 2 }]
    const changes = [{ tableId: 1, row: 1, col: 2 }, { tableId: 2, row: 1, col: 1 }]
    const { failed, final } = simulate(cur, planGridSave(cur, changes))
    expect(failed).toEqual([])
    expect(at(final, 1)).toMatchObject({ row: 1, col: 2 })
    expect(at(final, 2)).toMatchObject({ row: 1, col: 1 })
  })

  it('세 테이블을 돌려 앉혀도 성공한다', () => {
    const cur = [{ tableId: 1, row: 1, col: 1 }, { tableId: 2, row: 1, col: 2 }, { tableId: 3, row: 1, col: 3 }]
    const changes = [{ tableId: 1, row: 1, col: 2 }, { tableId: 2, row: 1, col: 3 }, { tableId: 3, row: 1, col: 1 }]
    expect(simulate(cur, planGridSave(cur, changes)).failed).toEqual([])
  })

  it('옮기지 않는 테이블이 있는 칸으로 가면 여전히 거절된다(진짜 중복)', () => {
    const cur = [{ tableId: 1, row: 1, col: 1 }, { tableId: 2, row: 2, col: 2 }]
    const changes = [{ tableId: 1, row: 2, col: 2 }]
    const { failed, final } = simulate(cur, planGridSave(cur, changes))
    expect(failed).toEqual([1])
    expect(at(final, 2)).toMatchObject({ row: 2, col: 2 }) // 가만히 있던 테이블은 그대로
  })

  it('빈 칸으로 옮기는 평범한 경우는 한 번만 보낸다', () => {
    const cur = [{ tableId: 1, row: 1, col: 1 }]
    expect(planGridSave(cur, [{ tableId: 1, row: 3, col: 3 }])).toEqual([{ tableId: 1, row: 3, col: 3 }])
  })

  it('처음 배치하는 테이블(좌표 없음)도 그대로 보낸다', () => {
    const cur = [{ tableId: 1, row: null, col: null }]
    expect(planGridSave(cur, [{ tableId: 1, row: 1, col: 1 }])).toEqual([{ tableId: 1, row: 1, col: 1 }])
  })

  it('35개를 한 줄씩 밀어도 실패가 없다', () => {
    const cur = Array.from({ length: 35 }, (_, i) => ({ tableId: i + 1, row: 1 + Math.floor(i / 7), col: 1 + (i % 7) }))
    const changes = cur.map((c, i) => ({ tableId: c.tableId, row: cur[(i + 1) % 35].row, col: cur[(i + 1) % 35].col }))
    const { failed, final } = simulate(cur, planGridSave(cur, changes))
    expect(failed).toEqual([])
    expect(new Set(final.map((t) => `${t.row},${t.col}`)).size).toBe(35)
  })
})
