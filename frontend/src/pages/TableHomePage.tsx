import { useEffect, useRef, useState } from 'react'
import PaymentModal from '../components/PaymentModal'
import PillButton from '../components/PillButton'
import TableGridCard from '../components/TableGridCard'
import TopNav from '../components/TopNav'
import { useTableOrders } from '../context/TableOrderContext'
import { GRID_CARD_SIZE, GRID_CELL_PITCH, pixelToGridIndex, resolveDrop, type PlacedTable } from '../lib/gridDrag'
import { planGridSave } from '../lib/gridSavePlan'
import { useNow } from '../lib/useNow'
import { shouldShowTableEmptyState } from '../lib/tableEmptyState'
import { getAuthToken } from '../lib/auth'
import { compareTableLabels, displayTableLabel } from '../lib/tableLabel'
import type { TableStatusInfo } from '../types/table'

const MIN_GRID_INDEX = 1
const MAX_GRID_INDEX = 50
const MIN_COLUMNS = 1
const MIN_ROWS = 1

type GridPos = { row: number | null; col: number | null }
// target(스냅된 칸)은 항상 pointermove/pointerdown 핸들러 안에서 미리 계산해 둔다
// (렌더 중 gridRef.current를 읽지 않기 위해) — clientX/Y는 커서를 그대로 따라가는 미리보기용 원시 좌표
type DragState = { tableId: number; clientX: number; clientY: number; target: { row: number; col: number } }

export default function TableHomePage() {
  const { tables, error, loaded, refetch, addTable, commitGridPosition, deleteTable } = useTableOrders()
  const [editMode, setEditMode] = useState(false)
  const [selectedTableId, setSelectedTableId] = useState<number | null>(null)
  // 편집 모드 중 드래그로 바꾼(아직 저장 안 한) 행/열 — "저장하기"를 눌러야 서버에 반영된다
  const [gridEdits, setGridEdits] = useState<Record<number, GridPos>>({})
  const [drag, setDrag] = useState<DragState | null>(null)
  // 추가·삭제·배치 저장 중 연타를 막는다 — 안 막으면 "테이블 추가"는 두 개 생기고, 삭제는 DELETE가 두 번 나간다
  const [tableActionBusy, setTableActionBusy] = useState(false)
  const [tableError, setTableError] = useState<string | null>(null)
  // 경과시간 색상 판정 기준 시각 — 2시간 임계값 판정이라 촘촘한 갱신은 필요 없다(최대 30초 늦게 빨강)
  const now = useNow(30_000)
  const gridRef = useRef<HTMLDivElement>(null)

  // 편집 중 드래그로 바뀐 자리(gridEdits)를 서버 값 위에 겹쳐 보여준다 — 저장 전까지는 화면에서만 보인다
  const currentPos = (table: TableStatusInfo): GridPos => gridEdits[table.id] ?? { row: table.gridRow, col: table.gridCol }

  const placedEntries = tables
    .map((table) => ({ table, pos: currentPos(table) }))
    .filter((e): e is { table: TableStatusInfo; pos: { row: number; col: number } } => e.pos.row != null && e.pos.col != null)
  const unplacedEntries = tables
    .map((table) => ({ table, pos: currentPos(table) }))
    .filter((e) => e.pos.row == null || e.pos.col == null)

  const selectedTable = tables.find((t) => t.id === selectedTableId) ?? null
  const columns = Math.max(MIN_COLUMNS, ...placedEntries.map((e) => e.pos.col))
  const rows = Math.max(MIN_ROWS, ...placedEntries.map((e) => e.pos.row))

  const handleAddTable = async () => {
    if (tableActionBusy) return
    setTableActionBusy(true)
    setTableError(null)
    try {
      await addTable()
    } catch {
      // addTable이 던지는 건 401(apiFetch가 로그인 화면으로 보내는 중)이나 망 끊김이다.
      if (getAuthToken()) setTableError('테이블을 추가하지 못했어요. 네트워크 상태를 확인해주세요.')
    } finally {
      setTableActionBusy(false)
    }
  }

  // 개별 카드마다 삭제 버튼을 두지 않고, 항상 라벨이 가장 큰(마지막) 테이블만 지운다
  const handleDeleteLastTable = async () => {
    if (tables.length === 0 || tableActionBusy) return
    const lastTable = tables.reduce((max, t) => (compareTableLabels(t.label, max.label) > 0 ? t : max))
    if (!window.confirm(`${displayTableLabel(lastTable.label)}을(를) 삭제할까요?`)) return
    setTableActionBusy(true)
    setTableError(null)
    try {
      await deleteTable(lastTable.id)
    } catch {
      if (getAuthToken()) setTableError('테이블을 삭제하지 못했어요. 네트워크 상태를 확인해주세요.')
    } finally {
      setTableActionBusy(false)
    }
  }

  // 포인터 위치를 그리드 컨테이너 기준 칸(행/열)으로 바꾼다 — 카드 중심이 손가락/커서 아래 오게 카드 절반만큼 보정.
  // 항상 이벤트 핸들러 안에서만 부른다 — 렌더 중에 gridRef.current를 읽지 않기 위해(react(refs) 경고 방지)
  const targetCellFor = (clientX: number, clientY: number) => {
    const rect = gridRef.current?.getBoundingClientRect()
    if (!rect) return null
    const relX = clientX - rect.left - GRID_CARD_SIZE / 2
    const relY = clientY - rect.top - GRID_CARD_SIZE / 2
    return {
      col: pixelToGridIndex(relX, MIN_GRID_INDEX, MAX_GRID_INDEX),
      row: pixelToGridIndex(relY, MIN_GRID_INDEX, MAX_GRID_INDEX),
    }
  }

  const startDrag = (tableId: number) => (e: React.PointerEvent) => {
    if (tableActionBusy) return
    const target = targetCellFor(e.clientX, e.clientY)
    if (target) setDrag({ tableId, clientX: e.clientX, clientY: e.clientY, target })
  }

  // 그립에서 setPointerCapture를 쓰면 이후 move/up 이벤트가 "포인터가 실제로 있는 곳"이 아니라
  // 캡처한 그립 엘리먼트 자신에서만 발생한다 — 그리드 컨테이너에 붙인 핸들러가 아예 못 받는다(실제로 겪은 버그,
  // 드롭이 조용히 무시되고 드래그 상태가 영영 안 풀렸다). 그래서 캡처 대신 document 전체에서 듣는다.
  useEffect(() => {
    if (drag === null) return
    const draggedTableId = drag.tableId

    const onMove = (e: PointerEvent) => {
      const target = targetCellFor(e.clientX, e.clientY)
      if (target) setDrag((prev) => (prev ? { ...prev, clientX: e.clientX, clientY: e.clientY, target } : prev))
    }

    const onUp = (e: PointerEvent) => {
      const target = targetCellFor(e.clientX, e.clientY)
      const draggedTable = tables.find((t) => t.id === draggedTableId)
      if (target && draggedTable) {
        const origin = currentPos(draggedTable)
        const others: PlacedTable[] = placedEntries
          .filter((entry) => entry.table.id !== draggedTableId)
          .map((entry) => ({ tableId: entry.table.id, row: entry.pos.row, col: entry.pos.col }))
        const changes = resolveDrop(draggedTableId, origin, target, others)
        if (changes.length > 0) {
          setGridEdits((prev) => {
            const next = { ...prev }
            for (const change of changes) next[change.tableId] = { row: change.row, col: change.col }
            return next
          })
        }
      }
      setDrag(null)
    }

    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
    // tableId가 바뀔 때만(드래그 시작/종료) 다시 구독한다 — 매 픽셀 이동마다 떼었다 붙이지 않는다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [drag?.tableId])

  const handleSaveGridEdits = async () => {
    if (tableActionBusy) return
    const changes = Object.entries(gridEdits)
      .map(([idStr, pos]) => ({ tableId: Number(idStr), row: pos.row, col: pos.col }))
      .filter((change) => {
        const table = tables.find((t) => t.id === change.tableId)
        return table && (change.row !== table.gridRow || change.col !== table.gridCol)
      })
    if (changes.length === 0) {
      setEditMode(false)
      return
    }

    setTableActionBusy(true)
    setTableError(null)
    const failedTableIds = new Set<number>()
    try {
      // 순차 저장 + 맞바꾸기 대비 — 순서만으로는 맞바꾸기가 409로 실패한다(lib/gridSavePlan.ts 주석)
      const current = tables.map((t) => ({ tableId: t.id, row: t.gridRow ?? null, col: t.gridCol ?? null }))
      for (const op of planGridSave(current, changes)) {
        if (failedTableIds.has(op.tableId)) continue
        const ok = await commitGridPosition(op.tableId, op.row, op.col)
        if (!ok) failedTableIds.add(op.tableId)
      }
    } finally {
      setTableActionBusy(false)
    }
    setGridEdits((prev) => {
      const next: Record<number, GridPos> = {}
      for (const [idStr, pos] of Object.entries(prev)) {
        if (failedTableIds.has(Number(idStr))) next[Number(idStr)] = pos
      }
      return next
    })
    if (failedTableIds.size === 0) setEditMode(false) // 전부 성공했을 때만 편집 모드를 닫는다
  }

  const draggedTable = drag ? (tables.find((t) => t.id === drag.tableId) ?? null) : null

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />

      <div className="flex justify-end gap-3 px-10 py-6">
        {editMode ? (
          <>
            <PillButton type="button" onClick={handleAddTable} disabled={tableActionBusy}>
              테이블 추가
            </PillButton>
            <PillButton type="button" onClick={handleDeleteLastTable} disabled={tables.length === 0 || tableActionBusy}>
              테이블 삭제
            </PillButton>
            <PillButton type="button" onClick={handleSaveGridEdits} disabled={tableActionBusy}>
              저장하기
            </PillButton>
          </>
        ) : (
          <>
            {/* TODO: 자리 이동·자리 합석은 스키마 설계가 더 필요해서 아직 동작 없음 */}
            <PillButton type="button" disabled>
              자리 이동
            </PillButton>
            <PillButton type="button" disabled>
              자리 합석
            </PillButton>
            <PillButton type="button" onClick={() => setEditMode(true)}>
              테이블 편집
            </PillButton>
          </>
        )}
      </div>

      {(error || tableError) && <p className="px-10 text-sm text-red-600">{error ?? tableError}</p>}

      {editMode && unplacedEntries.length > 0 && (
        <div className="border-b border-neutral-200 bg-neutral-100 px-10 py-4">
          <p className="mb-2 text-sm text-neutral-400">미배치 테이블 — 그립을 눌러 그리드로 드래그해 배치하세요</p>
          <div className="flex flex-wrap gap-4 pt-5">
            {unplacedEntries.map(({ table }) => (
              <TableGridCard
                key={table.id}
                table={table}
                editMode
                onClick={() => {}}
                now={now}
                onDragStart={startDrag(table.id)}
                dragging={drag?.tableId === table.id}
              />
            ))}
          </div>
        </div>
      )}

      {/* 배치된 테이블이 하나도 없을 때(신규 가입 직후 등)의 빈 화면 */}
      {shouldShowTableEmptyState({ editMode, loaded, error, placedCount: placedEntries.length }) && (
        <div className="flex flex-col items-center gap-4 px-10 py-24 text-center">
          <p className="text-lg text-neutral-400">
            {unplacedEntries.length > 0
              ? '위치가 없는 테이블이 있어요. 테이블 편집에서 그리드로 옮겨주세요.'
              : '아직 등록된 테이블이 없어요. 테이블 편집에서 테이블을 추가해보세요.'}
          </p>
          <PillButton type="button" onClick={() => setEditMode(true)}>
            테이블 편집
          </PillButton>
        </div>
      )}

      <div
        ref={gridRef}
        className={`relative mx-10 mb-10 ${editMode ? 'mt-10' : 'mt-6'}`}
        style={{ width: columns * GRID_CELL_PITCH, height: rows * GRID_CELL_PITCH }}
      >
        {placedEntries.map(({ table, pos }) => (
          <div
            key={table.id}
            className="absolute"
            style={{ left: (pos.col - 1) * GRID_CELL_PITCH, top: (pos.row - 1) * GRID_CELL_PITCH }}
          >
            <TableGridCard
              table={table}
              editMode={editMode}
              onClick={() => !editMode && setSelectedTableId(table.id)}
              now={now}
              onDragStart={editMode ? startDrag(table.id) : undefined}
              dragging={drag?.tableId === table.id}
            />
          </div>
        ))}

        {/* 드롭 대상 칸 미리보기 — 손을 떼면 여기로 옮겨진다 */}
        {drag && (
          <div
            className="absolute rounded-2xl border-2 border-dashed border-primary-200"
            style={{
              left: (drag.target.col - 1) * GRID_CELL_PITCH,
              top: (drag.target.row - 1) * GRID_CELL_PITCH,
              width: GRID_CARD_SIZE,
              height: GRID_CARD_SIZE,
            }}
          />
        )}
      </div>

      {/* 드래그 중인 카드가 손가락/커서를 그대로 따라오는 미리보기 */}
      {drag && draggedTable && (
        <div
          className="pointer-events-none fixed z-50"
          style={{ left: drag.clientX - GRID_CARD_SIZE / 2, top: drag.clientY - GRID_CARD_SIZE / 2 }}
        >
          <TableGridCard table={draggedTable} editMode={false} onClick={() => {}} now={now} />
        </div>
      )}

      {selectedTable && (
        <PaymentModal
          table={selectedTable}
          onClose={() => setSelectedTableId(null)}
          onCheckedOut={() => {
            setSelectedTableId(null)
            refetch()
          }}
        />
      )}
    </div>
  )
}
