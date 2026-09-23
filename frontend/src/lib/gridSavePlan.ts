export type GridCell = { tableId: number; row: number | null; col: number | null }

/**
 * 편집 모드에서 바꾼 행/열을 서버에 보낼 순서를 정한다.
 *
 * 서버(O22b)는 저장 순간 그 칸에 다른 테이블이 있으면 409로 거절한다. 그래서 두 테이블 자리를 맞바꾸면
 * (A:1,1 ↔ B:1,2) 순서와 상관없이 첫 요청 때 상대가 아직 그 칸에 있어 둘 다 실패했다. 35개를 실제로
 * 배치하면서 흔히 하는 동작이다.
 *
 * 1단계: 이번 변경의 목표 칸을 지금 차지하고 있는 "변경 대상" 테이블을 먼저 비운다.
 * 2단계: 목표 칸으로 옮긴다.
 * 변경 대상이 아닌 테이블이 목표 칸에 있으면 비우지 않는다 — 그건 진짜 중복이라 서버가 409를 줘야 맞다.
 */
export function planGridSave(current: GridCell[], changes: GridCell[]): GridCell[] {
  const key = (row: number | null, col: number | null) => `${row},${col}`
  const changing = new Set(changes.map((c) => c.tableId))
  const targets = new Set(changes.filter((c) => c.row != null && c.col != null).map((c) => key(c.row, c.col)))

  const clears: GridCell[] = []
  for (const change of changes) {
    const now = current.find((t) => t.tableId === change.tableId)
    if (!now || now.row == null || now.col == null) continue
    if (changing.has(now.tableId) && targets.has(key(now.row, now.col))) {
      clears.push({ tableId: now.tableId, row: null, col: null })
    }
  }
  // 비우기만 하는 변경(행/열을 지움)은 1단계에서 이미 끝났으면 2단계에서 다시 보낼 필요가 없다
  const cleared = new Set(clears.map((c) => c.tableId))
  const moves = changes.filter((c) => !(cleared.has(c.tableId) && c.row == null && c.col == null))
  return [...clears, ...moves]
}
