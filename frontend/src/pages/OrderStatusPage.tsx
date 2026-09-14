import { useEffect, useMemo, useState } from 'react'
import OrderCard from '../components/OrderCard'
import TopNav from '../components/TopNav'
import { mockOrders } from '../data/mockOrders'
import type { OrderStatus } from '../types/dashboard'

const TABS: { status: OrderStatus; label: string }[] = [
  { status: 'RECEIVED', label: '진행' },
  { status: 'DONE', label: '완료' },
  { status: 'CANCELED', label: '취소' },
]

export default function OrderStatusPage() {
  const [orders, setOrders] = useState(mockOrders)
  const [activeStatus, setActiveStatus] = useState<OrderStatus>('RECEIVED')
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(id)
  }, [])

  const counts = useMemo(() => {
    const map: Record<OrderStatus, number> = { RECEIVED: 0, DONE: 0, CANCELED: 0 }
    for (const order of orders) map[order.status]++
    return map
  }, [orders])

  const visibleOrders = orders.filter((order) => order.status === activeStatus)

  // TODO: API 연결 시 PATCH /admin/orders/{id}/complete, POST /admin/orders/{id}/cancel 호출로 교체
  const completeOrder = (orderId: number) => {
    setOrders((prev) => prev.map((o) => (o.orderId === orderId ? { ...o, status: 'DONE' } : o)))
  }
  const cancelOrder = (orderId: number) => {
    setOrders((prev) => prev.map((o) => (o.orderId === orderId ? { ...o, status: 'CANCELED' } : o)))
  }
  // TODO: "되돌리기"는 백엔드에 해당 엔드포인트가 아직 없음 (complete/cancel 되돌리는 API 없음) — 지금은 로컬 상태만 되돌림
  const revertOrder = (orderId: number) => {
    setOrders((prev) => prev.map((o) => (o.orderId === orderId ? { ...o, status: 'RECEIVED' } : o)))
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
        {visibleOrders.length === 0 && (
          <p className="col-span-full py-20 text-center text-neutral-400">해당하는 주문이 없어요.</p>
        )}
      </div>
    </div>
  )
}
