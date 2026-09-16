import { useEffect, useState } from 'react'
import PaymentModal from '../components/PaymentModal'
import PillButton from '../components/PillButton'
import TableGridCard from '../components/TableGridCard'
import TopNav from '../components/TopNav'
import { useTableOrders } from '../context/TableOrderContext'
import { compareTableLabels, displayTableLabel } from '../lib/tableLabel'

const CARD_WIDTH = 200
const CARD_HEIGHT = 240
const HANDLE_SPACE = 40
const CANVAS_SIDE_PADDING = 40 // 캔버스 컨테이너의 px-10(좌우 각 40px)

export default function TableHomePage() {
  const { tables, error, addTable, moveTable, commitTablePosition, placeUnplacedTable, deleteTable } =
    useTableOrders()
  const [editMode, setEditMode] = useState(false)
  const [selectedTableId, setSelectedTableId] = useState<number | null>(null)
  const [viewportWidth, setViewportWidth] = useState(() => document.documentElement.clientWidth)

  // 운영자 화면은 태블릿 폭 기준 — 회전 등으로 폭이 바뀌어도 드래그 가능 범위를 다시 계산한다
  useEffect(() => {
    const onResize = () => setViewportWidth(document.documentElement.clientWidth)
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  // 테이블이 화면 폭 밖으로 드래그되지 않게 막는 상한 — 이 값을 넘기면 캔버스가 화면보다 넓어져 가로 스크롤이 생긴다
  const maxX = Math.max(0, viewportWidth - CANVAS_SIDE_PADDING * 2 - CARD_WIDTH)

  const unplacedTables = tables.filter((t) => t.posX == null || t.posY == null)
  const selectedTable = tables.find((t) => t.id === selectedTableId) ?? null

  // 화면 폭이 좁아졌거나(태블릿 회전 등) 예전에 더 넓은 화면에서 저장된 위치라도, 항상 현재 화면 안에 들어오게
  // 렌더링 시점에 다시 clamp한다 — 그래야 drag로 직접 옮기지 않아도 다시 열었을 때 가로 스크롤이 안 생긴다.
  const placedTables = tables
    .filter((t) => t.posX != null && t.posY != null)
    .map((t) => ({ ...t, posX: Math.min(t.posX!, maxX) }))

  // 드래그로 오른쪽/아래로 멀리 옮겨도 카드가 잘리지 않게 캔버스 크기를 내용에 맞춰 키운다
  const canvasHeight = Math.max(0, ...placedTables.map((t) => t.posY ?? 0)) + CARD_HEIGHT + HANDLE_SPACE + 40
  const canvasWidth = Math.max(0, ...placedTables.map((t) => t.posX ?? 0)) + CARD_WIDTH + 80

  // 개별 카드마다 삭제 버튼을 두지 않고, 항상 라벨이 가장 큰(마지막) 테이블만 지운다
  // (서버도 마지막 테이블만 삭제를 허용 — 그 외엔 409, 사용 중이면 마찬가지로 409)
  const handleDeleteLastTable = () => {
    if (tables.length === 0) return
    const lastTable = tables.reduce((max, t) => (compareTableLabels(t.label, max.label) > 0 ? t : max))
    if (window.confirm(`${displayTableLabel(lastTable.label)}을(를) 삭제할까요?`)) {
      deleteTable(lastTable.id)
    }
  }

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />

      <div className="flex justify-end gap-3 px-10 py-6">
        {editMode ? (
          <>
            <PillButton type="button" onClick={addTable}>
              테이블 추가
            </PillButton>
            <PillButton type="button" onClick={handleDeleteLastTable} disabled={tables.length === 0}>
              테이블 삭제
            </PillButton>
            <PillButton type="button" onClick={() => setEditMode(false)}>
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

      {error && <p className="px-10 text-sm text-red-600">{error}</p>}

      {editMode && unplacedTables.length > 0 && (
        <div className="border-b border-neutral-200 bg-neutral-100 px-10 py-4">
          <p className="mb-2 text-sm text-neutral-400">미배치 테이블 — 눌러서 배치도에 놓기</p>
          <div className="flex flex-wrap gap-3">
            {unplacedTables.map((t) => (
              <button
                key={t.id}
                type="button"
                onClick={() => placeUnplacedTable(t.id)}
                className="rounded-xl border border-neutral-300 bg-neutral-50 px-4 py-3 text-base font-semibold text-neutral-900"
              >
                {displayTableLabel(t.label)}
              </button>
            ))}
          </div>
        </div>
      )}

      <div
        className="relative px-10 pb-10"
        style={{ height: canvasHeight, minWidth: canvasWidth, marginTop: HANDLE_SPACE }}
      >
        {placedTables.map((table) => (
          <TableGridCard
            key={table.id}
            table={table}
            editMode={editMode}
            onClick={() => !editMode && setSelectedTableId(table.id)}
            onMove={moveTable}
            onDragEnd={commitTablePosition}
            maxX={maxX}
          />
        ))}
      </div>

      {selectedTable && <PaymentModal table={selectedTable} onClose={() => setSelectedTableId(null)} />}
    </div>
  )
}
