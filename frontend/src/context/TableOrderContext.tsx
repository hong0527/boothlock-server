import { createContext, useContext, useState, type ReactNode } from 'react'
import type { TableOrder } from '../types/table'

/**
 * 백엔드에 테이블별 주문 집계 API·좌표 컬럼이 아직 없어서 쓰는 로컬 상태.
 * TODO: 연동 시 이 Context를 걷어내고 GET /admin/tables(O3) + 주문 집계 + position_x/y로 교체.
 */
type TableOrderContextValue = {
  tables: TableOrder[]
  addTable: () => void
  updateItemQty: (tableId: number, itemId: number, delta: number) => void
  cancelItem: (tableId: number, itemId: number) => void
  cancelAllItems: (tableId: number) => void
  completePayment: (tableId: number) => void
  updateTablePosition: (tableId: number, x: number, y: number) => void
}

const TableOrderContext = createContext<TableOrderContextValue | null>(null)

const sampleItems = [
  { id: 1, name: '묵은지 김치찜', unitPrice: 12000, qty: 1 },
  { id: 2, name: '계란찜', unitPrice: 6000, qty: 1 },
  { id: 3, name: '소주', unitPrice: 4000, qty: 2 },
  { id: 4, name: '맥주', unitPrice: 8000, qty: 3 },
]

const COLUMNS = 5
const COL_STRIDE = 224 // 카드 200 + gap 24
const ROW_STRIDE = 264 // 카드 240 + gap 24
const CARD_WIDTH = 200
const CARD_HEIGHT = 240

const gridPosition = (index: number) => ({
  x: (index % COLUMNS) * COL_STRIDE,
  y: Math.floor(index / COLUMNS) * ROW_STRIDE,
})

// 실제 테이블처럼 같은 자리를 두 카드가 차지할 수 없게 막는다 (겹치면 뒤 카드가 사라져서 못 찾는 문제 방지)
const overlaps = (a: { x: number; y: number }, b: { x: number; y: number }) =>
  a.x < b.x + CARD_WIDTH && a.x + CARD_WIDTH > b.x && a.y < b.y + CARD_HEIGHT && a.y + CARD_HEIGHT > b.y

// 격자 순번대로 자리를 찾되, 드래그로 옮겨진 테이블과 겹치면 다음 칸으로 넘어간다
const findFreeGridPosition = (existing: TableOrder[]) => {
  let index = existing.length
  while (existing.some((t) => overlaps(gridPosition(index), t))) index++
  return gridPosition(index)
}

const initialTables: TableOrder[] = Array.from({ length: 9 }, (_, i) => ({
  id: i + 1,
  label: `테이블 - ${i + 1}`,
  startedAt: new Date().toISOString(),
  items: sampleItems.map((item) => ({ ...item })),
  ...gridPosition(i),
}))

export function TableOrderProvider({ children }: { children: ReactNode }) {
  const [tables, setTables] = useState<TableOrder[]>(initialTables)

  const addTable = () => {
    setTables((prev) => [
      ...prev,
      {
        id: Math.max(0, ...prev.map((t) => t.id)) + 1,
        label: `테이블 - ${prev.length + 1}`,
        startedAt: new Date().toISOString(),
        items: [],
        ...findFreeGridPosition(prev),
      },
    ])
  }

  const updateItemQty: TableOrderContextValue['updateItemQty'] = (tableId, itemId, delta) => {
    setTables((prev) =>
      prev.map((t) =>
        t.id !== tableId
          ? t
          : {
              ...t,
              items: t.items.map((item) =>
                item.id === itemId ? { ...item, qty: Math.max(1, item.qty + delta) } : item,
              ),
            },
      ),
    )
  }

  const cancelItem: TableOrderContextValue['cancelItem'] = (tableId, itemId) => {
    setTables((prev) =>
      prev.map((t) => (t.id !== tableId ? t : { ...t, items: t.items.filter((item) => item.id !== itemId) })),
    )
  }

  const cancelAllItems: TableOrderContextValue['cancelAllItems'] = (tableId) => {
    setTables((prev) => prev.map((t) => (t.id !== tableId ? t : { ...t, items: [] })))
  }

  const completePayment: TableOrderContextValue['completePayment'] = (tableId) => {
    // 결제 완료 = 테이블 비움 (주문 내역 초기화)
    setTables((prev) => prev.map((t) => (t.id !== tableId ? t : { ...t, items: [] })))
  }

  const updateTablePosition: TableOrderContextValue['updateTablePosition'] = (tableId, x, y) => {
    setTables((prev) => {
      const candidate = { x, y }
      const collides = prev.some((t) => t.id !== tableId && overlaps(candidate, t))
      if (collides) return prev // 다른 테이블과 겹치는 위치로는 이동 거부
      return prev.map((t) => (t.id !== tableId ? t : { ...t, x, y }))
    })
  }

  return (
    <TableOrderContext.Provider
      value={{
        tables,
        addTable,
        updateItemQty,
        cancelItem,
        cancelAllItems,
        completePayment,
        updateTablePosition,
      }}
    >
      {children}
    </TableOrderContext.Provider>
  )
}

export function useTableOrders() {
  const ctx = useContext(TableOrderContext)
  if (!ctx) throw new Error('useTableOrders는 TableOrderProvider 안에서만 쓸 수 있어요')
  return ctx
}
