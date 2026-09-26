/** 테이블 편집 화면의 드래그 배치 — 카드 96px + 간격 16px, Figma(686:2223)의 셀 간격(≈120px)에 맞춘 값 */
export const GRID_CARD_SIZE = 96
export const GRID_GAP = 16
export const GRID_CELL_PITCH = GRID_CARD_SIZE + GRID_GAP

export function clampIndex(index: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, index))
}

/** 그리드 컨테이너 기준 픽셀 좌표(카드 좌상단)를 가장 가까운 1-베이스 행/열로 스냅한다 */
export function pixelToGridIndex(px: number, min: number, max: number): number {
  return clampIndex(Math.round(px / GRID_CELL_PITCH) + min, min, max)
}

export type DropTarget = { row: number; col: number }
export type PlacedTable = { tableId: number; row: number; col: number }

/**
 * 드롭 대상 칸에 다른 테이블이 이미 있으면 그 테이블을 드래그 시작 전 칸으로 보낸다(맞바꾸기).
 * originRow/originCol이 null이면(미배치 트레이에서 드래그) 그 테이블은 미배치로 돌아간다.
 * 반환값은 이번 드롭으로 바뀌는 항목만 담는다(대상 자신 + 밀려난 테이블, 있다면).
 */
export type GridChange = { tableId: number; row: number | null; col: number | null }

export function resolveDrop(
  draggedTableId: number,
  origin: { row: number | null; col: number | null },
  target: DropTarget,
  placed: PlacedTable[],
): GridChange[] {
  if (origin.row === target.row && origin.col === target.col) return []
  const occupant = placed.find((t) => t.tableId !== draggedTableId && t.row === target.row && t.col === target.col)
  const changes: GridChange[] = [{ tableId: draggedTableId, row: target.row, col: target.col }]
  if (occupant) changes.push({ tableId: occupant.tableId, row: origin.row, col: origin.col })
  return changes
}

/** 새 테이블을 "테이블 추가"로 만들면 바로 앉힐 자리 — 행 우선으로 훑어 처음 비어있는 칸.
 * columns는 한 행에 둘 칸 수(Figma 686:2223 예시가 10열이라 그 값을 기본으로 쓴다) */
export function nextFreeCell(placed: DropTarget[], columns: number, maxIndex: number): DropTarget {
  const occupied = new Set(placed.map((p) => `${p.row},${p.col}`))
  for (let row = 1; row <= maxIndex; row++) {
    for (let col = 1; col <= columns; col++) {
      if (!occupied.has(`${row},${col}`)) return { row, col }
    }
  }
  return { row: maxIndex, col: columns } // 이론상 꽉 찼을 때 — 마지막 칸에 겹쳐 놓고 사용자가 옮기게 한다
}
