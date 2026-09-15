import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import OrderCard from '../components/OrderCard'
import TopNav from '../components/TopNav'
import { apiFetch } from '../lib/apiFetch'
import { todayKst } from '../lib/time'
import type { OrderStatus, OrderSummary } from '../types/dashboard'

const TABS: { status: OrderStatus; label: string }[] = [
  { status: 'RECEIVED', label: '진행' },
  { status: 'DONE', label: '완료' },
  { status: 'CANCELED', label: '취소' },
]

const POLL_INTERVAL_MS = 5000 // 명세서 O10 권장 폴링 주기(3~5초)

type OrdersByStatus = Record<OrderStatus, OrderSummary[]>

async function fetchOrdersByStatus(status: OrderStatus): Promise<OrderSummary[]> {
  const res = await apiFetch(`/api/v1/admin/orders?status=${status}&businessDate=${todayKst()}`)
  if (!res.ok) throw new Error(`주문 목록을 불러오지 못했어요 (${res.status})`)
  const data: { orders: OrderSummary[] } = await res.json()
  return data.orders
}

export default function OrderStatusPage() {
  const [ordersByStatus, setOrdersByStatus] = useState<OrdersByStatus>({ RECEIVED: [], DONE: [], CANCELED: [] })
  const [activeStatus, setActiveStatus] = useState<OrderStatus>('RECEIVED')
  const [error, setError] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  // "되돌리기"는 백엔드에 엔드포인트가 없어 로컬로만 되돌린다 — 폴링이 서버 값으로 덮어쓰지 않도록
  // 되돌린 주문을 여기 보관해뒀다가 매 refetch마다 다시 얹는다. complete/cancel로 다시 실제 액션을
  // 취하면(그때는 서버 상태가 진짜 바뀌므로) 지운다.
  const revertedOrdersRef = useRef<Map<number, OrderSummary>>(new Map())

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(id)
  }, [])

  const refetchAll = useCallback(async () => {
    try {
      const [received, done, canceled] = await Promise.all(
        TABS.map((tab) => fetchOrdersByStatus(tab.status)),
      )
      const overrides = revertedOrdersRef.current
      const hasOverride = (o: OrderSummary) => overrides.has(o.orderId)
      setOrdersByStatus({
        RECEIVED: [...overrides.values(), ...received.filter((o) => !hasOverride(o))],
        DONE: done.filter((o) => !hasOverride(o)),
        CANCELED: canceled.filter((o) => !hasOverride(o)),
      })
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : '주문 목록을 불러오지 못했어요.')
    }
  }, [])

  useEffect(() => {
    refetchAll()
    const id = setInterval(refetchAll, POLL_INTERVAL_MS)
    return () => clearInterval(id)
  }, [refetchAll])

  const counts = useMemo(
    () => ({
      RECEIVED: ordersByStatus.RECEIVED.length,
      DONE: ordersByStatus.DONE.length,
      CANCELED: ordersByStatus.CANCELED.length,
    }),
    [ordersByStatus],
  )

  const visibleOrders = ordersByStatus[activeStatus]

  const completeOrder = async (orderId: number) => {
    const res = await apiFetch(`/api/v1/admin/orders/${orderId}/complete`, { method: 'PATCH' })
    if (!res.ok) {
      setError(`주문을 완료 처리하지 못했어요 (${res.status})`)
      return
    }
    revertedOrdersRef.current.delete(orderId)
    setError(null)
    refetchAll()
  }

  const cancelOrder = async (orderId: number) => {
    const res = await apiFetch(`/api/v1/admin/orders/${orderId}/cancel`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    })
    if (!res.ok) {
      setError(`주문을 취소 처리하지 못했어요 (${res.status})`)
      return
    }
    revertedOrdersRef.current.delete(orderId)
    setError(null)
    refetchAll()
  }

  // "되돌리기"는 백엔드에 해당 엔드포인트가 아직 없음 — 로컬 화면 상태만 되돌리고,
  // revertedOrdersRef에 보관해서 폴링(refetchAll)이 서버 값으로 다시 덮어쓰지 못하게 한다
  const revertOrder = (orderId: number) => {
    setOrdersByStatus((prev) => {
      let reverted: OrderSummary | null = null
      const next: OrdersByStatus = { RECEIVED: prev.RECEIVED, DONE: [], CANCELED: [] }
      for (const status of ['DONE', 'CANCELED'] as const) {
        next[status] = prev[status].filter((o) => {
          if (o.orderId === orderId) {
            reverted = { ...o, status: 'RECEIVED' }
            return false
          }
          return true
        })
      }
      if (!reverted) return prev
      revertedOrdersRef.current.set(orderId, reverted)
      return { ...next, RECEIVED: [reverted, ...next.RECEIVED] }
    })
  }

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />

      <div className="flex justify-center gap-16 border-b border-neutral-100 bg-neutral-50 px-10 py-5">
        {TABS.map((tab) => (
          <button
            key={tab.status}
            type="button"
            onClick={() => setActiveStatus(tab.status)}
            className={`pb-2 text-[22px] leading-[1.2] font-semibold tracking-[-0.04em] ${
              activeStatus === tab.status
                ? 'border-b-2 border-neutral-900 text-neutral-900'
                : 'text-neutral-400'
            }`}
          >
            {tab.label} {counts[tab.status]}
          </button>
        ))}
      </div>

      {error && <p className="px-10 pt-4 text-sm text-red-600">{error}</p>}

      <div className="grid grid-cols-1 gap-6 p-10 md:grid-cols-2 lg:grid-cols-3">
        {visibleOrders.map((order) => (
          <OrderCard
            key={order.orderId}
            order={order}
            now={now}
            onComplete={completeOrder}
            onCancel={cancelOrder}
            onRevert={revertOrder}
          />
        ))}
        {visibleOrders.length === 0 && !error && (
          <p className="col-span-full py-20 text-center text-neutral-400">해당하는 주문이 없어요.</p>
        )}
      </div>
    </div>
  )
}
