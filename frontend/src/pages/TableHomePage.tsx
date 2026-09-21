import { useEffect, useState } from 'react'
import PaymentModal from '../components/PaymentModal'
import PillButton from '../components/PillButton'
import TableGridCard from '../components/TableGridCard'
import TopNav from '../components/TopNav'
import { useTableOrders } from '../context/TableOrderContext'
import { getAuthToken } from '../lib/auth'
import { displayTableLabel } from '../lib/tableLabel'

const CARD_WIDTH = 200
const CARD_HEIGHT = 240
const HANDLE_SPACE = 40
const CANVAS_SIDE_PADDING = 40 // 캔버스 컨테이너의 px-10(좌우 각 40px)

export default function TableHomePage() {
  const { tables, error, refetch, addTable, moveTable, commitTablePosition, placeUnplacedTable } = useTableOrders()
  const [editMode, setEditMode] = useState(false)
  const [selectedTableId, setSelectedTableId] = useState<number | null>(null)
  const [viewportWidth, setViewportWidth] = useState(() => document.documentElement.clientWidth)
  // "테이블 추가" 연타를 막는다 — 안 막으면 같은 클릭이 두 번 나가 테이블이 두 개 생길 수 있다
  const [tableActionBusy, setTableActionBusy] = useState(false)
  // 망 끊김처럼 context의 error에 안 담기는 실패를 이 화면에서 알린다
  const [tableError, setTableError] = useState<string | null>(null)

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

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />

      <div className="flex justify-end gap-3 px-10 py-6">
        {editMode ? (
          <>
            <PillButton type="button" onClick={handleAddTable} disabled={tableActionBusy}>
              테이블 추가
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

      {(error || tableError) && <p className="px-10 text-sm text-red-600">{error ?? tableError}</p>}

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
