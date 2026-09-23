import { useState } from 'react'
import PaymentModal from '../components/PaymentModal'
import PillButton from '../components/PillButton'
import TableGridCard from '../components/TableGridCard'
import TopNav from '../components/TopNav'
import { useTableOrders } from '../context/TableOrderContext'
import { planGridSave } from '../lib/gridSavePlan'
import { useNow } from '../lib/useNow'
import { shouldShowTableEmptyState } from '../lib/tableEmptyState'
import { getAuthToken } from '../lib/auth'
import { compareTableLabels, displayTableLabel } from '../lib/tableLabel'

const MIN_GRID_INDEX = 1
const MAX_GRID_INDEX = 50
/** 실제 쓴 열 수만큼만 그린다 — 8로 두면 너비가 최소 1848px라 태블릿(1180~1366px)에서 항상 가로 스크롤이 생긴다 */
const MIN_COLUMNS = 1

type GridDraft = { row: string; col: string }

export default function TableHomePage() {
  const { tables, error, loaded, refetch, addTable, commitGridPosition, deleteTable } = useTableOrders()
  const [editMode, setEditMode] = useState(false)
  const [selectedTableId, setSelectedTableId] = useState<number | null>(null)
  // 편집 모드 중 아직 저장하지 않은 행/열 입력값 — "저장하기"를 눌러야 서버에 반영된다
  const [gridEdits, setGridEdits] = useState<Record<number, GridDraft>>({})
  // 미배치 트레이 — 어떤 테이블에 행/열 입력창을 열어뒀는지
  const [placingId, setPlacingId] = useState<number | null>(null)
  const [placingDraft, setPlacingDraft] = useState<GridDraft>({ row: '', col: '' })
  // 추가·삭제·배치 중 연타를 막는다 — 안 막으면 "테이블 추가"는 테이블이 두 개 생기고, "테이블 삭제"는 같은 테이블에
  // DELETE가 두 번 나가 하나는 204, 하나는 404가 뜬다(실제 재현됨)
  const [tableActionBusy, setTableActionBusy] = useState(false)
  // 망 끊김처럼 context의 error에 안 담기는 실패를 이 화면에서 알린다
  const [tableError, setTableError] = useState<string | null>(null)
  // 경과시간 색상 판정 기준 시각 — 2시간 임계값 판정이라 촘촘한 갱신은 필요 없다(최대 30초 늦게 빨강)
  const now = useNow(30_000)

  const unplacedTables = tables.filter((t) => t.gridRow == null || t.gridCol == null)
  const placedTables = tables.filter((t) => t.gridRow != null && t.gridCol != null)
  const selectedTable = tables.find((t) => t.id === selectedTableId) ?? null
  const columns = Math.max(MIN_COLUMNS, ...placedTables.map((t) => t.gridCol ?? 0))

  const handleAddTable = async () => {
    if (tableActionBusy) return
    setTableActionBusy(true)
    setTableError(null)
    try {
      await addTable()
    } catch {
      // addTable이 던지는 건 401(apiFetch가 로그인 화면으로 보내는 중)이나 망 끊김이다.
      // catch가 없으면 예외가 그대로 빠져나가 버튼만 흐려졌다 돌아오고 아무 표시도 안 된다
      if (getAuthToken()) setTableError('테이블을 추가하지 못했어요. 네트워크 상태를 확인해주세요.')
    } finally {
      setTableActionBusy(false)
    }
  }

  // 개별 카드마다 삭제 버튼을 두지 않고, 항상 라벨이 가장 큰(마지막) 테이블만 지운다
  // (서버도 마지막 테이블만 삭제를 허용 — 그 외엔 409, 사용 중이면 마찬가지로 409)
  const handleDeleteLastTable = async () => {
    if (tables.length === 0 || tableActionBusy) return
    const lastTable = tables.reduce((max, t) => (compareTableLabels(t.label, max.label) > 0 ? t : max))
    if (!window.confirm(`${displayTableLabel(lastTable.label)}을(를) 삭제할까요?`)) return
    setTableActionBusy(true)
    setTableError(null)
    try {
      await deleteTable(lastTable.id)
    } catch {
      // handleAddTable과 같은 이유 — 401 이동 중이거나 망 끊김
      if (getAuthToken()) setTableError('테이블을 삭제하지 못했어요. 네트워크 상태를 확인해주세요.')
    } finally {
      setTableActionBusy(false)
    }
  }

  const parseGridDraft = (label: string, draft: GridDraft): { row: number | null; col: number | null } | null => {
    const row = draft.row.trim()
    const col = draft.col.trim()
    if (row === '' && col === '') return { row: null, col: null }
    if (row === '' || col === '') {
      setTableError(`${displayTableLabel(label)}의 행/열을 모두 입력하거나 모두 비워주세요.`)
      return null
    }
    const rowNum = Number(row)
    const colNum = Number(col)
    if (
      !Number.isInteger(rowNum) ||
      !Number.isInteger(colNum) ||
      rowNum < MIN_GRID_INDEX ||
      rowNum > MAX_GRID_INDEX ||
      colNum < MIN_GRID_INDEX ||
      colNum > MAX_GRID_INDEX
    ) {
      setTableError(`${displayTableLabel(label)}의 행/열은 ${MIN_GRID_INDEX}~${MAX_GRID_INDEX} 사이의 숫자여야 해요.`)
      return null
    }
    return { row: rowNum, col: colNum }
  }

  // 편집 모드에서 바꾼 행/열을 한 번에 저장 — 드래그와 달리 입력은 즉시 반영되지 않고 "저장하기"를 눌러야 서버로 나간다
  const handleSaveGridEdits = async () => {
    if (tableActionBusy) return
    const changes: { tableId: number; row: number | null; col: number | null }[] = []
    for (const [idStr, draft] of Object.entries(gridEdits)) {
      const tableId = Number(idStr)
      const table = tables.find((t) => t.id === tableId)
      if (!table) continue
      const parsed = parseGridDraft(table.label, draft)
      if (parsed === null) return // 검증 실패 — 저장 중단, 안내만 띄운다
      if (parsed.row === table.gridRow && parsed.col === table.gridCol) continue // 변화 없음
      changes.push({ tableId, ...parsed })
    }

    setTableActionBusy(true)
    setTableError(null)
    // 실패한 항목의 입력값은 지우지 않는다 — 중복 좌표 같은 오류로 실패해도 다시 타이핑하지 않고 고쳐서 재시도할 수 있게
    const failedTableIds = new Set<number>()
    try {
      // 순차 저장 + 맞바꾸기 대비 — 순서만으로는 맞바꾸기가 409로 실패한다(lib/gridSavePlan.ts 주석).
      // 목표 칸을 차지한 변경 대상을 먼저 비우고 옮긴다. 한 번 실패한 테이블은 뒤 단계도 건너뛴다.
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
      const next: Record<number, GridDraft> = {}
      for (const [idStr, draft] of Object.entries(prev)) {
        if (failedTableIds.has(Number(idStr))) next[Number(idStr)] = draft
      }
      return next
    })
    if (failedTableIds.size === 0) setEditMode(false) // 전부 성공했을 때만 편집 모드를 닫는다
  }

  const handleConfirmPlacement = async (tableId: number) => {
    const table = tables.find((t) => t.id === tableId)
    if (!table || tableActionBusy) return
    const parsed = parseGridDraft(table.label, placingDraft)
    if (parsed === null || parsed.row === null || parsed.col === null) {
      if (parsed !== null) setTableError(`${displayTableLabel(table.label)}의 행/열을 입력해주세요.`)
      return
    }
    setTableActionBusy(true)
    setTableError(null)
    let ok = false
    try {
      ok = await commitGridPosition(tableId, parsed.row, parsed.col)
    } finally {
      setTableActionBusy(false)
    }
    // 실패(예: 중복 좌표)했을 때는 입력창을 닫지 않는다 — 값을 그대로 두고 고쳐서 다시 시도할 수 있게
    if (ok) {
      setPlacingId(null)
      setPlacingDraft({ row: '', col: '' })
    }
  }

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

      {editMode && unplacedTables.length > 0 && (
        <div className="border-b border-neutral-200 bg-neutral-100 px-10 py-4">
          <p className="mb-2 text-sm text-neutral-400">미배치 테이블 — 눌러서 행/열 번호를 입력하고 배치하기</p>
          <div className="flex flex-wrap gap-3">
            {unplacedTables.map((t) =>
              placingId === t.id ? (
                <div key={t.id} className="flex items-center gap-2 rounded-xl border border-neutral-300 bg-neutral-50 px-4 py-3">
                  <span className="text-base font-semibold text-neutral-900">{displayTableLabel(t.label)}</span>
                  <input
                    type="number"
                    min={MIN_GRID_INDEX}
                    max={MAX_GRID_INDEX}
                    placeholder="행"
                    aria-label={`${displayTableLabel(t.label)} 행 번호`}
                    value={placingDraft.row}
                    onChange={(e) => setPlacingDraft({ row: e.target.value, col: placingDraft.col })}
                    className="w-14 rounded-lg border border-neutral-300 px-2 py-1 text-center text-sm"
                  />
                  <input
                    type="number"
                    min={MIN_GRID_INDEX}
                    max={MAX_GRID_INDEX}
                    placeholder="열"
                    aria-label={`${displayTableLabel(t.label)} 열 번호`}
                    value={placingDraft.col}
                    onChange={(e) => setPlacingDraft({ row: placingDraft.row, col: e.target.value })}
                    className="w-14 rounded-lg border border-neutral-300 px-2 py-1 text-center text-sm"
                  />
                  <PillButton type="button" onClick={() => handleConfirmPlacement(t.id)} disabled={tableActionBusy}>
                    배치
                  </PillButton>
                  <button
                    type="button"
                    onClick={() => setPlacingId(null)}
                    className="text-sm text-neutral-400"
                    aria-label="배치 취소"
                  >
                    취소
                  </button>
                </div>
              ) : (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => {
                    setPlacingId(t.id)
                    setPlacingDraft({ row: '', col: '' })
                  }}
                  className="rounded-xl border border-neutral-300 bg-neutral-50 px-4 py-3 text-base font-semibold text-neutral-900"
                >
                  {displayTableLabel(t.label)}
                </button>
              ),
            )}
          </div>
        </div>
      )}

      {/* 배치된 테이블이 하나도 없을 때(신규 가입 직후 등)의 빈 화면 — "테이블 편집"을 눌러야 "테이블
          추가"가 나타나는 걸 모르면 화면이 완전히 비어 보여서 막막하다. 편집모드 진입 버튼 자체는 이미
          위에 있으니 여기서는 안내 문구 + 같은 동작의 버튼 하나만 더해 바로 다음 행동을 알려준다. */}
      {shouldShowTableEmptyState({ editMode, loaded, error, placedCount: placedTables.length }) && (
        <div className="flex flex-col items-center gap-4 px-10 py-24 text-center">
          <p className="text-lg text-neutral-400">
            {unplacedTables.length > 0
              ? '위치(행/열)가 없는 테이블이 있어요. 테이블 편집에서 행과 열을 입력해주세요.'
              : '아직 등록된 테이블이 없어요. 테이블 편집에서 테이블을 추가해보세요.'}
          </p>
          <PillButton type="button" onClick={() => setEditMode(true)}>
            테이블 편집
          </PillButton>
        </div>
      )}

      <div className="overflow-x-auto px-10 pb-10">
        <div
          className="grid gap-6"
          style={{ gridTemplateColumns: `repeat(${columns}, 200px)`, marginTop: 24 }}
        >
          {placedTables.map((table) => (
            <div key={table.id} style={{ gridRow: table.gridRow ?? undefined, gridColumn: table.gridCol ?? undefined }}>
              <TableGridCard
                table={table}
                editMode={editMode}
                onClick={() => !editMode && setSelectedTableId(table.id)}
                gridDraft={
                  editMode
                    ? (gridEdits[table.id] ?? { row: String(table.gridRow ?? ''), col: String(table.gridCol ?? '') })
                    : undefined
                }
                onGridDraftChange={
                  editMode ? (draft) => setGridEdits((prev) => ({ ...prev, [table.id]: draft })) : undefined
                }
                now={now}
              />
            </div>
          ))}
        </div>
      </div>

      {/* 위치(행/열)가 없는 테이블도 평소 화면에서 보여야 한다. 안 보이면 손님은 QR로 주문하는데
          운영자는 카드가 없어 결제 모달을 열 수 없다. 이 기능 배포 직후엔 기존 테이블 전부가 이 상태로
          시작하고, 축제 중 "테이블 추가"로 만든 테이블도 행/열을 넣기 전까지 이 상태다. */}
      {!editMode && unplacedTables.length > 0 && placedTables.length > 0 && (
        <div className="px-10 pb-10">
          <p className="mb-3 text-sm text-neutral-400">위치 미지정 테이블 — "테이블 편집"에서 행과 열을 입력하세요</p>
          <div className="flex flex-wrap gap-6">
            {unplacedTables.map((table) => (
              <div key={table.id} className="w-[200px]">
                <TableGridCard table={table} editMode={false} onClick={() => setSelectedTableId(table.id)} now={now} />
              </div>
            ))}
          </div>
        </div>
      )}
      {!editMode && unplacedTables.length > 0 && placedTables.length === 0 && loaded && !error && (
        <div className="flex flex-wrap justify-center gap-6 px-10 pb-10">
          {unplacedTables.map((table) => (
            <div key={table.id} className="w-[200px]">
              <TableGridCard table={table} editMode={false} onClick={() => setSelectedTableId(table.id)} now={now} />
            </div>
          ))}
        </div>
      )}

      {selectedTable && (
        <PaymentModal
          table={selectedTable}
          onClose={() => setSelectedTableId(null)}
          // 퇴실 직후 5초 폴링을 기다리지 않고 카드가 바로 비게 목록을 다시 읽는다
          onCheckedOut={() => {
            setSelectedTableId(null)
            refetch()
          }}
        />
      )}
    </div>
  )
}
