import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { apiFetch } from '../lib/apiFetch'
import { createPollGuard } from '../lib/pollGuard'
import { isOrderOfSession } from '../lib/sessionOrders'
import type { OrderSummary } from '../types/dashboard'
import type { TableStatusInfo } from '../types/table'
import { onResume } from '../lib/onResume'

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
  /** 테이블 목록을 한 번이라도 제대로 받았는지 — 받기 전의 빈 배열을 "테이블 없음"으로 읽으면 안 된다 */
  loaded: boolean
  /** 폴링을 기다리지 않고 즉시 다시 읽는다 — 퇴실 직후 카드가 바로 비게 */
  refetch: () => Promise<TableStatusInfo[]>
  /** 새 테이블의 id를 돌려준다(자동 배치용) — 요청 자체가 실패하면 null */
  addTable: () => Promise<number | null>
  /** 드래그 중 로컬 좌표만 갱신(시각 피드백) — 서버 저장은 commitTablePosition이 한다 */
  moveTable: (tableId: number, x: number, y: number) => void
  /** O22 — 드래그가 끝났을 때(pointerup) 한 번 호출해 저장한다 */
  commitTablePosition: (tableId: number, x: number, y: number) => Promise<void>
  /** 미배치 테이블을 빈 격자 자리에 배치한다 (px 드래그 방식 — 파일럿 이후 재사용 예정) */
  placeUnplacedTable: (tableId: number) => Promise<void>
  /** O22b — 파일럿 전용, 운영자가 직접 입력한 행/열을 저장한다. 둘 다 null이면 미배치로 되돌린다. 성공 여부를 돌려준다 —
   * 실패(예: 중복 좌표 409)했을 때 호출자가 사용자 입력을 그대로 남겨둘지(재시도 가능하게) 판단하는 데 쓴다 */
  commitGridPosition: (tableId: number, row: number | null, col: number | null) => Promise<boolean>
  /** 테이블 삭제(숨김 처리) — 사용 중이거나 마지막 번호가 아니면 서버가 409로 거부한다.
   * 성공(404=이미 지워짐 포함) 여부를 돌려준다 — 일괄 삭제 시 호출자가 어디서 멈출지 판단하는 데 쓴다 */
  deleteTable: (tableId: number) => Promise<boolean>
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

type TableOrderAggregate = Pick<TableStatusInfo, 'orderItems' | 'orderTotal' | 'firstOrderAt'>

// 테이블-홈(Figma)은 카드에 항목별 수량·합계를 보여준다 — O3엔 없는 값이라 O10 주문 목록을 테이블별로 묶어서 계산한다.
// O10은 그 영업일의 모든 세션 주문을 주므로 지금 앉은 손님 것만 센다 — 주문의 sessionId와 O3 session.id(세션 PK)를 맞춘다.
// 테이블마다 `?tableId=&activeSessionOnly=true`를 따로 부르면 5초 폴링마다 테이블 수만큼 요청이 늘어서, 한 번 조회한 목록을 나눈다.
// 취소된 주문은 제외(진행+완료만 현재 테이블 이용 내역으로 침). 세션이 없는(유휴 포함) 테이블은 빈 카드.
const aggregateTableOrders = (
  table: Pick<TableStatusInfo, 'session'>,
  orders: OrderSummary[],
): TableOrderAggregate => {
  const items = new Map<string, number>()
  let total = 0
  let firstOrderAt: string | null = null
  for (const order of orders) {
    if (order.status === 'CANCELED') continue
    if (!isOrderOfSession(order, table.session)) continue
    total += order.totalAmount
    if (firstOrderAt === null || Date.parse(order.createdAt) < Date.parse(firstOrderAt)) firstOrderAt = order.createdAt
    for (const item of order.items) {
      items.set(item.menuName, (items.get(item.menuName) ?? 0) + item.qty)
    }
  }
  return { orderItems: Array.from(items, ([menuName, qty]) => ({ menuName, qty })), orderTotal: total, firstOrderAt }
}

export function TableOrderProvider({ children }: { children: ReactNode }) {
  const [tables, setTables] = useState<TableStatusInfo[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)

  const pollGuard = useRef(createPollGuard())

  // skipIfBusy는 폴링에서만 켠다 — 공개 refetch는 퇴실 직후 즉시 갱신에 쓰이므로 건너뛰면 안 된다
  const runRefetch = useCallback(async (skipIfBusy: boolean): Promise<TableStatusInfo[]> => {
    const runId = pollGuard.current.begin(skipIfBusy)
    if (runId === null) return []
    try {
      const res = await apiFetch('/api/v1/admin/tables')
      if (!res.ok) throw new Error(`테이블 목록을 불러오지 못했어요 (${res.status})`)
      const data: { tables: Omit<TableStatusInfo, keyof TableOrderAggregate>[] } = await res.json()

      // 주문 집계는 실패해도 테이블 목록 자체는 보여준다 — 카드에 항목만 비게 나올 뿐
      let orders: OrderSummary[] = []
      // businessDate 생략 = 서버가 현재 영업일(06:00 경계)로 기본 처리 — 프론트 달력 날짜를 보내면 새벽에 전날 주문이 빠진다
      const ordersRes = await apiFetch('/api/v1/admin/orders')
      if (ordersRes.ok) {
        const ordersData: { orders: OrderSummary[] } = await ordersRes.json()
        orders = ordersData.orders
      }

      const merged: TableStatusInfo[] = data.tables.map((t) => ({ ...t, ...aggregateTableOrders(t, orders) }))

      // 나중에 시작된 요청이 있으면 화면은 덮지 않는다 — 호출자에게는 방금 읽은 값을 그대로 돌려준다
      if (!pollGuard.current.isLatest(runId)) return merged
      setTables(merged)
      setError(null)
      setLoaded(true)
      return merged
    } catch (err) {
      if (pollGuard.current.isLatest(runId)) {
        setError(err instanceof Error ? err.message : '테이블 목록을 불러오지 못했어요.')
      }
      return []
    } finally {
      pollGuard.current.end()
    }
  }, [])

  const refetch = useCallback(() => runRefetch(false), [runRefetch])

  useEffect(() => {
    runRefetch(false)
    const id = setInterval(() => runRefetch(true), POLL_INTERVAL_MS)
    const offResume = onResume(() => runRefetch(true))
    return () => {
      clearInterval(id)
      offResume()
    }
  }, [runRefetch])

  const addTable: TableOrderContextValue['addTable'] = async () => {
    // 라벨 번호는 프론트가 계산하지 않는다 — 부스별 영구 카운터로 서버가 채번(삭제해도 재사용 안 함)
    const res = await apiFetch('/api/v1/admin/tables', { method: 'POST' })
    if (!res.ok) {
      setError(`테이블을 추가하지 못했어요 (${res.status})`)
      return null
    }
    // 새 테이블은 항상 미배치(그리드 좌표 없음) 상태로 생겨서, 호출자가 바로 자리를 배치할 수 있게 id를 돌려준다
    const created: { id: number } = await res.json()
    await refetch()
    return created.id
  }

  const deleteTable: TableOrderContextValue['deleteTable'] = async (tableId) => {
    const res = await apiFetch(`/api/v1/admin/tables/${tableId}`, { method: 'DELETE' })
    // 404는 실패가 아니라 이미 지워진 상태다 — 버튼 연타나 다른 기기의 동시 삭제로 중복 요청이 나가도
    // (실제로 재현됨: 같은 테이블에 DELETE 두 번 보내면 하나는 204, 하나는 404) "실패했다"고 잘못 띄우지 않는다
    if (res.status === 404) {
      setError(null)
      setTables((prev) => prev.filter((t) => t.id !== tableId))
      return true
    }
    if (!res.ok) {
      // 서버 메시지를 그대로 보여준다 — "사용 중" / "마지막 번호만" 두 가지 409 사유를 구분해서 알려줄 수 있게
      const body: { error?: { message?: string } } | null = await res.json().catch(() => null)
      setError(body?.error?.message ?? `테이블을 삭제하지 못했어요 (${res.status})`)
      return false
    }
    setError(null)
    setTables((prev) => prev.filter((t) => t.id !== tableId))
    return true
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
    // O22 응답엔 orderItems/orderTotal/firstOrderAt이 없다(프론트 계산 필드) — 기존 값을 덮어쓰지 않게 얹어준다
    const updated: Omit<TableStatusInfo, keyof TableOrderAggregate> = await res.json()
    setTables((prev) => prev.map((t) => (t.id === tableId ? { ...t, ...updated } : t)))
  }

  const commitGridPosition: TableOrderContextValue['commitGridPosition'] = async (tableId, row, col) => {
    const res = await apiFetch(`/api/v1/admin/tables/${tableId}/grid-position`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ row, col }),
    })
    if (!res.ok) {
      setError(
        res.status === 409 ? '이미 다른 테이블이 배치된 칸이에요.' : `테이블 위치를 저장하지 못했어요 (${res.status})`,
      )
      return false
    }
    // O22b 응답도 O22와 마찬가지로 orderItems/orderTotal/firstOrderAt이 없다 — 기존 값을 덮어쓰지 않게 얹어준다
    const updated: Omit<TableStatusInfo, keyof TableOrderAggregate> = await res.json()
    setTables((prev) => prev.map((t) => (t.id === tableId ? { ...t, ...updated } : t)))
    return true
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
      value={{
        tables,
        error,
        loaded,
        refetch,
        addTable,
        moveTable,
        commitTablePosition,
        placeUnplacedTable,
        commitGridPosition,
        deleteTable,
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
