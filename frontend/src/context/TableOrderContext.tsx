import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { apiFetch } from '../lib/apiFetch'
import { todayKst } from '../lib/time'
import type { OrderSummary } from '../types/dashboard'
import type { TableStatusInfo } from '../types/table'

const POLL_INTERVAL_MS = 5000

const COL_STRIDE = 224 // 카드 200 + gap 24
const ROW_STRIDE = 264 // 카드 240 + gap 24
const CARD_WIDTH = 200
const CARD_HEIGHT = 240
const CANVAS_SIDE_PADDING = 40 // TableHomePage 캔버스 컨테이너의 px-10(좌우 각 40px)과 맞춘 값

// 운영자 화면은 태블릿 폭 기준 — 화면 폭에 맞는 컬럼 수만큼만 옆으로 배치해 가로 스크롤이 생기지 않게 한다
const columnsForViewport = () => {
  const usableWidth = document.documentElement.clientWidth - CANVAS_SIDE_PADDING * 2
  return Math.max(1, Math.floor((usableWidth + 24) / COL_STRIDE))
}

type TableOrderContextValue = {
  tables: TableStatusInfo[]
  error: string | null
  addTable: () => Promise<void>
  /** 드래그 중 로컬 좌표만 갱신(시각 피드백) — 서버 저장은 commitTablePosition이 한다 */
  moveTable: (tableId: number, x: number, y: number) => void
  /** O22 — 드래그가 끝났을 때(pointerup) 한 번 호출해 저장한다 */
  commitTablePosition: (tableId: number, x: number, y: number) => Promise<void>
  /** 미배치 테이블을 빈 격자 자리에 배치한다 */
  placeUnplacedTable: (tableId: number) => Promise<void>
  /** 테이블 삭제(숨김 처리) — 사용 중인 테이블은 서버가 409로 거부한다 */
  deleteTable: (tableId: number) => Promise<void>
}

const TableOrderContext = createContext<TableOrderContextValue | null>(null)

const gridPosition = (index: number, columns: number) => ({
  x: (index % columns) * COL_STRIDE,
  y: Math.floor(index / columns) * ROW_STRIDE,
})

// 실제 테이블처럼 같은 자리를 두 카드가 차지할 수 없게 막는다 (겹치면 뒤 카드가 사라져서 못 찾는 문제 방지)
const overlaps = (a: { x: number; y: number }, b: { x: number; y: number }) =>
  a.x < b.x + CARD_WIDTH && a.x + CARD_WIDTH > b.x && a.y < b.y + CARD_HEIGHT && a.y + CARD_HEIGHT > b.y

// 격자 순번대로 빈자리를 찾되, 이미 배치된 테이블과 겹치면 다음 칸으로 넘어간다
const findFreeGridPosition = (placed: { x: number; y: number }[]) => {
  const columns = columnsForViewport()
  let index = placed.length
  while (placed.some((p) => overlaps(gridPosition(index, columns), p))) index++
  return gridPosition(index, columns)
}

// 테이블-홈(Figma)은 카드에 항목별 수량·합계를 보여준다 — O3엔 없는 값이라 O10 주문 목록을
// tableLabel로 묶어서 계산한다. 취소된 주문은 제외(진행+완료만 현재 테이블 이용 내역으로 침)
const buildOrderIndexByTableLabel = (orders: OrderSummary[]) => {
  const index = new Map<string, { items: Map<string, number>; total: number }>()
  for (const order of orders) {
    if (order.status === 'CANCELED' || !order.tableLabel) continue
    const entry = index.get(order.tableLabel) ?? { items: new Map<string, number>(), total: 0 }
    entry.total += order.totalAmount
    for (const item of order.items) {
      entry.items.set(item.menuName, (entry.items.get(item.menuName) ?? 0) + item.qty)
    }
    index.set(order.tableLabel, entry)
  }
  return index
}

export function TableOrderProvider({ children }: { children: ReactNode }) {
  const [tables, setTables] = useState<TableStatusInfo[]>([])
  const [error, setError] = useState<string | null>(null)

  const refetch = useCallback(async (): Promise<TableStatusInfo[]> => {
    try {
      const res = await apiFetch('/api/v1/admin/tables')
      if (!res.ok) throw new Error(`테이블 목록을 불러오지 못했어요 (${res.status})`)
      const data: { tables: Omit<TableStatusInfo, 'orderItems' | 'orderTotal'>[] } = await res.json()

      // 주문 집계는 실패해도 테이블 목록 자체는 보여준다 — 카드에 항목만 비게 나올 뿐
      let orderIndex = new Map<string, { items: Map<string, number>; total: number }>()
      const ordersRes = await apiFetch(`/api/v1/admin/orders?businessDate=${todayKst()}`)
      if (ordersRes.ok) {
        const ordersData: { orders: OrderSummary[] } = await ordersRes.json()
        orderIndex = buildOrderIndexByTableLabel(ordersData.orders)
      }

      const merged: TableStatusInfo[] = data.tables.map((t) => {
        const entry = orderIndex.get(t.label)
        return {
          ...t,
          orderItems: entry ? Array.from(entry.items, ([menuName, qty]) => ({ menuName, qty })) : [],
          orderTotal: entry?.total ?? 0,
        }
      })

      setTables(merged)
      setError(null)
      return merged
    } catch (err) {
      setError(err instanceof Error ? err.message : '테이블 목록을 불러오지 못했어요.')
      return []
    }
  }, [])

  useEffect(() => {
    refetch()
    const id = setInterval(refetch, POLL_INTERVAL_MS)
    return () => clearInterval(id)
  }, [refetch])

  const addTable = async () => {
    // 라벨 번호는 프론트가 계산하지 않는다 — 부스별 영구 카운터로 서버가 채번(삭제해도 재사용 안 함)
    const res = await apiFetch('/api/v1/admin/tables', { method: 'POST' })
    if (!res.ok) {
      setError(`테이블을 추가하지 못했어요 (${res.status})`)
      return
    }
    const created: { id: number } = await res.json()

    // 미배치 상태로 두고 사람이 드래그해서 놓게 하지 않고, 바로 빈 격자 자리에 배치까지 해버린다
    const latest = await refetch()
    const placed = latest.filter((t) => t.posX != null && t.posY != null) as (TableStatusInfo & {
      posX: number
      posY: number
    })[]
    const { x, y } = findFreeGridPosition(placed.map((t) => ({ x: t.posX, y: t.posY })))
    await commitTablePosition(created.id, x, y)
  }

  const deleteTable = async (tableId: number) => {
    const res = await apiFetch(`/api/v1/admin/tables/${tableId}`, { method: 'DELETE' })
    if (!res.ok) {
      setError(
        res.status === 409 ? '사용 중인 테이블은 삭제할 수 없어요.' : `테이블을 삭제하지 못했어요 (${res.status})`,
      )
      return
    }
    setError(null)
    setTables((prev) => prev.filter((t) => t.id !== tableId))
  }

  const moveTable: TableOrderContextValue['moveTable'] = (tableId, x, y) => {
    setTables((prev) => prev.map((t) => (t.id !== tableId ? t : { ...t, posX: x, posY: y })))
  }

  const commitTablePosition: TableOrderContextValue['commitTablePosition'] = async (tableId, x, y) => {
    const res = await apiFetch(`/api/v1/admin/tables/${tableId}/position`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ posX: x, posY: y }),
    })
    if (!res.ok) {
      setError(`테이블 위치를 저장하지 못했어요 (${res.status})`)
      return
    }
    // O22 응답엔 orderItems/orderTotal이 없다(O3 확장 필드) — 기존 값을 덮어쓰지 않게 얹어준다
    const updated: Omit<TableStatusInfo, 'orderItems' | 'orderTotal'> = await res.json()
    setTables((prev) => prev.map((t) => (t.id === tableId ? { ...t, ...updated } : t)))
  }

  const placeUnplacedTable: TableOrderContextValue['placeUnplacedTable'] = async (tableId) => {
    const placed = tables.filter((t) => t.posX != null && t.posY != null) as (TableStatusInfo & {
      posX: number
      posY: number
    })[]
    const { x, y } = findFreeGridPosition(placed.map((t) => ({ x: t.posX, y: t.posY })))
    await commitTablePosition(tableId, x, y)
  }

  return (
    <TableOrderContext.Provider
      value={{ tables, error, addTable, moveTable, commitTablePosition, placeUnplacedTable, deleteTable }}
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
