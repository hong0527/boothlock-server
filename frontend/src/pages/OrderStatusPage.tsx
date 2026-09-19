import { useCallback, useEffect, useMemo, useState } from 'react'
import OrderCard from '../components/OrderCard'
import TopNav from '../components/TopNav'
import { apiFetch } from '../lib/apiFetch'
import {
  ackCall,
  cancelOrder as cancelOrderRequest,
  completeOrder as completeOrderRequest,
  deleteOrder as deleteOrderRequest,
  restoreOrder as restoreOrderRequest,
} from '../lib/orderActions'
import { displayTableLabel } from '../lib/tableLabel'
import { formatElapsed } from '../lib/time'
import {
  CALL_REASON_LABEL,
  type CallSummary,
  type DashboardResponse,
  type OrderStatus,
  type OrderSummary,
} from '../types/dashboard'

const TABS: { status: OrderStatus; label: string }[] = [
  { status: 'RECEIVED', label: '진행' },
  { status: 'DONE', label: '완료' },
  { status: 'CANCELED', label: '취소' },
]

const POLL_INTERVAL_MS = 5000 // 명세서 O10 권장 폴링 주기(3~5초)

type OrdersByStatus = Record<OrderStatus, OrderSummary[]>

// businessDate는 보내지 않는다 — 서버 기본값이 현재 영업일(06:00 경계)이라 새벽에도 전날 영업일 주문이 그대로 보인다.
// 응답의 calls(미확인 호출)는 status 필터와 무관하게 부스 전체가 실려 온다 — 세 번 중 한 응답에서만 읽으면 된다
async function fetchDashboard(status: OrderStatus): Promise<DashboardResponse> {
  const res = await apiFetch(`/api/v1/admin/orders?status=${status}`)
  if (!res.ok) throw new Error(`주문 목록을 불러오지 못했어요 (${res.status})`)
  return res.json()
}

export default function OrderStatusPage() {
  const [ordersByStatus, setOrdersByStatus] = useState<OrdersByStatus>({ RECEIVED: [], DONE: [], CANCELED: [] })
  const [calls, setCalls] = useState<CallSummary[]>([])
  const [activeStatus, setActiveStatus] = useState<OrderStatus>('RECEIVED')
  const [error, setError] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  // 같은 주문에 대한 액션 버튼 연타로 요청이 중복 전송되는 걸 막는다 (예: 취소복구 더블클릭 → 두 번째 요청이 409)
  const [pendingOrderId, setPendingOrderId] = useState<number | null>(null)

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(id)
  }, [])

  const refetchAll = useCallback(async () => {
    try {
      const [received, done, canceled] = await Promise.all(TABS.map((tab) => fetchDashboard(tab.status)))
      setOrdersByStatus({ RECEIVED: received.orders, DONE: done.orders, CANCELED: canceled.orders })
      setCalls(received.calls ?? [])
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

  // O15 호출 확인 — 성공하면 서버 calls에서 빠진다. 폴링을 기다리지 않고 바로 지운다(멱등이라 중복 눌림도 안전)
  const acknowledgeCall = async (callId: number) => {
    const res = await ackCall(callId)
    if (!res.ok && res.status !== 404) {
      setError(`호출을 확인 처리하지 못했어요 (${res.status})`)
      return
    }
    setCalls((prev) => prev.filter((c) => c.callId !== callId))
    setError(null)
  }

  // 같은 주문에 대한 중복 요청을 막고 완료 후 pending을 해제한다
  const runOrderAction = async (orderId: number, action: () => Promise<Response>, failMessage: string) => {
    if (pendingOrderId === orderId) return
    setPendingOrderId(orderId)
    try {
      const res = await action()
      if (!res.ok) {
        setError(`${failMessage} (${res.status})`)
        return
      }
      setError(null)
      refetchAll()
    } finally {
      setPendingOrderId(null)
    }
  }

  const completeOrder = (orderId: number) =>
    runOrderAction(orderId, () => completeOrderRequest(orderId), '주문을 완료 처리하지 못했어요')

  const cancelOrder = (orderId: number) =>
    runOrderAction(orderId, () => cancelOrderRequest(orderId), '주문을 취소 처리하지 못했어요')

  // 취소복구 — CANCELED→RECEIVED만 되돌리고 결제/환불 상태는 건드리지 않는다
  const restoreOrder = (orderId: number) => {
    if (!window.confirm('이 주문을 진행 상태로 복구할까요?')) return
    return runOrderAction(orderId, () => restoreOrderRequest(orderId), '주문을 복구하지 못했어요')
  }

  // 삭제 — 실제 데이터 삭제가 아니라 주문현황 목록에서만 제외(hidden 처리)
  const deleteOrder = (orderId: number) => {
    if (!window.confirm('이 취소 주문을 삭제할까요?')) return
    return runOrderAction(orderId, () => deleteOrderRequest(orderId), '주문을 삭제하지 못했어요')
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

      {/* 미확인 직원 호출(O10 calls) — 주문 카드와 같은 카드 톤으로 한 줄씩, '확인'을 누르면 O15 */}
      {calls.length > 0 && (
        <div className="flex flex-wrap gap-3 px-10 pt-6">
          {calls.map((call) => (
            <div
              key={call.callId}
              className="flex items-center gap-4 rounded-xl border border-neutral-200 bg-neutral-50 px-4 py-3"
            >
              <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
                {displayTableLabel(call.tableLabel)}
              </span>
              <span className="text-base tracking-[-0.04em] text-neutral-900">
                {CALL_REASON_LABEL[call.reason] ?? call.reason}
              </span>
              <span className="text-sm text-blue-600">{formatElapsed(call.createdAt, now)}</span>
              <button
                type="button"
                onClick={() => acknowledgeCall(call.callId)}
                className="rounded-xl bg-neutral-600 px-4 py-2 text-base leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-50"
              >
                확인
              </button>
            </div>
          ))}
        </div>
      )}

      <div className="grid grid-cols-1 gap-6 p-10 md:grid-cols-2 lg:grid-cols-3">
        {visibleOrders.map((order) => (
          <OrderCard
            key={order.orderId}
            order={order}
            now={now}
            pending={pendingOrderId === order.orderId}
            onComplete={completeOrder}
            onCancel={cancelOrder}
            onRestore={restoreOrder}
            onDelete={deleteOrder}
          />
        ))}
        {visibleOrders.length === 0 && !error && (
          <p className="col-span-full py-20 text-center text-neutral-400">해당하는 주문이 없어요.</p>
        )}
      </div>
    </div>
  )
}
