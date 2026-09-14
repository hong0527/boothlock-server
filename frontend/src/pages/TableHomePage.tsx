import { useState } from 'react'
import PaymentModal from '../components/PaymentModal'
import PillButton from '../components/PillButton'
import TableGridCard from '../components/TableGridCard'
import TopNav from '../components/TopNav'
import { useTableOrders } from '../context/TableOrderContext'

const CARD_WIDTH = 200
const CARD_HEIGHT = 240
const HANDLE_SPACE = 40

export default function TableHomePage() {
  const { tables, addTable, updateTablePosition } = useTableOrders()
  const [editMode, setEditMode] = useState(false)
  const [selectedTableId, setSelectedTableId] = useState<number | null>(null)

  const selectedTable = tables.find((t) => t.id === selectedTableId) ?? null
  // 드래그로 오른쪽/아래로 멀리 옮겨도 카드가 잘리지 않게 캔버스 크기를 내용에 맞춰 키운다
  const canvasHeight = Math.max(0, ...tables.map((t) => t.y)) + CARD_HEIGHT + HANDLE_SPACE + 40
  const canvasWidth = Math.max(0, ...tables.map((t) => t.x)) + CARD_WIDTH + 80

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />

      <div className="flex justify-end gap-3 px-10 py-6">
        {editMode ? (
          <>
            <PillButton type="button" onClick={addTable}>
              테이블 추가
            </PillButton>
            {/* TODO: 배치는 로컬 상태일 뿐 — 백엔드에 position_x/y 컬럼 생기면 저장 API로 교체 */}
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

      <div
        className="relative px-10 pb-10"
        style={{ height: canvasHeight, minWidth: canvasWidth, marginTop: HANDLE_SPACE }}
      >
        {tables.map((table) => (
          <TableGridCard
            key={table.id}
            table={table}
            editMode={editMode}
            onClick={() => !editMode && setSelectedTableId(table.id)}
            onMove={updateTablePosition}
          />
        ))}
      </div>

      {selectedTable && <PaymentModal table={selectedTable} onClose={() => setSelectedTableId(null)} />}
    </div>
  )
}
