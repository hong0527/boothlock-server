import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import OrderCard from '../components/OrderCard'
import TopNav from '../components/TopNav'
import { apiFetch } from '../lib/apiFetch'
import { getAuthToken } from '../lib/auth'
import { createPollGuard } from '../lib/pollGuard'
import {
  ackCall,
  cancelOrder as cancelOrderRequest,
  completeOrder as completeOrderRequest,
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
  // 진행 중인 액션의 주문들. 상태(렌더용)와 ref(판정용)를 같이 둔다 — 상태만 보면 같은 렌더 사이클 안의
  // 더블클릭이 옛 값을 읽고 통과한다. 단일 값으로 두면 A가 끝날 때 아직 요청 중인 B의 잠금까지 풀린다
  const [pendingOrderIds, setPendingOrderIds] = useState<ReadonlySet<number>>(() => new Set())
  const pendingRef = useRef<Set<number>>(new Set())

  const pollGuard = useRef(createPollGuard())

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(id)
  }, [])

  /** skipIfBusy는 폴링에서만 켠다 — 액션 직후의 즉시 갱신까지 건너뛰면 화면이 안 바뀐다 (pollGuard 주석 참조) */
  const refetchAll = useCallback(async ({ skipIfBusy = false }: { skipIfBusy?: boolean } = {}) => {
    const runId = pollGuard.current.begin(skipIfBusy)
    if (runId === null) return
    try {
      const [received, done, canceled] = await Promise.all(TABS.map((tab) => fetchDashboard(tab.status)))
      if (!pollGuard.current.isLatest(runId)) return
      setOrdersByStatus({ RECEIVED: received.orders, DONE: done.orders, CANCELED: canceled.orders })
      setCalls(received.calls ?? [])
      setError(null)
    } catch (err) {
      if (!pollGuard.current.isLatest(runId)) return
      setError(err instanceof Error ? err.message : '주문 목록을 불러오지 못했어요.')
    } finally {
      pollGuard.current.end()
    }
  }, [])

  useEffect(() => {
    refetchAll()
    const id = setInterval(() => refetchAll({ skipIfBusy: true }), POLL_INTERVAL_MS)
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
    try {
      const res = await ackCall(callId)
      if (!res.ok && res.status !== 404) {
        setError(`호출을 확인 처리하지 못했어요 (${res.status})`)
        return
      }
      setCalls((prev) => prev.filter((c) => c.callId !== callId))
      setError(null)
    } catch {
      // 401이면 apiFetch가 이미 로그인 화면으로 보내는 중 — 그때는 문구를 덧그리지 않는다
      if (getAuthToken()) setError('호출을 확인 처리하지 못했어요. 네트워크 상태를 확인해주세요.')
    }
  }

  /**
   * 같은 주문에 대한 중복 요청을 막고 끝나면 해제한다.
   * 판정을 ref로 하는 이유 — 상태만 보면 리렌더 전에 들어온 두 번째 클릭이 옛 값(없음)을 읽고 통과한다.
   * catch가 있어야 하는 이유 — 없으면 망이 끊겼을 때 예외가 그대로 빠져나가 운영자에게 아무 표시도 안 된다.
   * 버튼만 잠깐 흐려졌다 돌아와서 "왜 안 눌리지" 하며 계속 누르게 된다(축제장 와이파이에서 자주 나올 상황).
   */
  const runOrderAction = async (orderId: number, action: () => Promise<Response>, failMessage: string) => {
    if (pendingRef.current.has(orderId)) return
    pendingRef.current.add(orderId)
    setPendingOrderIds(new Set(pendingRef.current))
    try {
      const res = await action()
      if (!res.ok) {
        setError(`${failMessage} (${res.status})`)
        return
      }
      setError(null)
      await refetchAll()
    } catch {
      if (getAuthToken()) setError(`${failMessage} — 서버에 연결할 수 없어요. 네트워크 상태를 확인해주세요.`)
    } finally {
      pendingRef.current.delete(orderId)
      setPendingOrderIds(new Set(pendingRef.current))
    }
  }

  const completeOrder = (orderId: number) =>
    runOrderAction(orderId, () => completeOrderRequest(orderId), '주문을 완료 처리하지 못했어요')

  const cancelOrder = (orderId: number) =>
    runOrderAction(orderId, () => cancelOrderRequest(orderId), '주문을 취소 처리하지 못했어요')

  // 되돌리기 — 완료·취소된 주문을 진행(RECEIVED)으로 되돌린다. 결제/환불 상태는 건드리지 않는다
  const restoreOrder = (orderId: number) => {
    if (!window.confirm('이 주문을 진행 상태로 복구할까요?')) return
    return runOrderAction(orderId, () => restoreOrderRequest(orderId), '주문을 복구하지 못했어요')
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
                className="rounded-xl bg-primary-300 px-4 py-2 text-base leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-50"
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
            pending={pendingOrderIds.has(order.orderId)}
            onComplete={completeOrder}
            onCancel={cancelOrder}
            onRestore={restoreOrder}
          />
        ))}
        {visibleOrders.length === 0 && !error && (
          <p className="col-span-full py-20 text-center text-neutral-400">해당하는 주문이 없어요.</p>
        )}
      </div>
    </div>
  )
}
