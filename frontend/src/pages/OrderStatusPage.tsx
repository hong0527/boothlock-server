import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import OrderCard from '../components/OrderCard'
import TopNav from '../components/TopNav'
import { apiFetch } from '../lib/apiFetch'
import { getAuthToken, getStaff } from '../lib/auth'
import { orderedForTab } from '../lib/dashboardOrders'
import { createPollGuard } from '../lib/pollGuard'
import {
  ackCall,
  approveOrder as approveOrderRequest,
  cancelOrder as cancelOrderRequest,
  completeOrder as completeOrderRequest,
  refundDone as refundDoneRequest,
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
import { onResume } from '../lib/onResume'

// O28(v0.6.10) — 승인대기를 맨 앞에 둔다(Figma "주문현황-승인대기" 641:1362 탭 순서)
const TABS: { status: OrderStatus; label: string }[] = [
  { status: 'PENDING_APPROVAL', label: '승인 대기' },
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
  // 환불 완료는 ADMIN 전용(백엔드 403) — STAFF에게는 눌러도 안 되는 버튼을 보여주지 않는다
  const isAdmin = getStaff()?.role === 'ADMIN'
  const [ordersByStatus, setOrdersByStatus] = useState<OrdersByStatus>({
    PENDING_APPROVAL: [], RECEIVED: [], DONE: [], CANCELED: [],
  })
  const [calls, setCalls] = useState<CallSummary[]>([])
  // 기본 진입 탭은 승인대기 — 새로 들어온 주문 중 운영자가 지금 당장 반응해야 할 것부터 보여준다 (Figma 641:1362)
  const [activeStatus, setActiveStatus] = useState<OrderStatus>('PENDING_APPROVAL')
  const [error, setError] = useState<string | null>(null)
  // 버튼 작업(완료·취소·복구·호출확인) 오류는 따로 둔다 — error는 폴링이 성공할 때마다 지워서, 한데 두면
  // "이미 다른 상태로 바뀌었어요" 같은 안내가 5초 안에 사라져 바쁜 운영자가 못 본다. 다음 작업이 성공하면 지운다
  const [actionError, setActionError] = useState<string | null>(null)
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
      const results = await Promise.all(TABS.map((tab) => fetchDashboard(tab.status)))
      if (!pollGuard.current.isLatest(runId)) return
      setOrdersByStatus(
        Object.fromEntries(TABS.map((tab, i) => [tab.status, results[i].orders])) as OrdersByStatus,
      )
      // calls(미확인 호출)는 상태 필터와 무관하게 부스 전체가 실려 온다 — 어느 응답에서 읽어도 같다. 승인대기가
      // 첫 탭이라 그 응답에서 읽는다(예전엔 RECEIVED 응답 기준이었다)
      setCalls(results[0].calls ?? [])
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
    const offResume = onResume(() => refetchAll({ skipIfBusy: true }))
    return () => {
      clearInterval(id)
      offResume()
    }
  }, [refetchAll])

  const counts = useMemo(
    () => Object.fromEntries(TABS.map((tab) => [tab.status, ordersByStatus[tab.status].length])) as Record<OrderStatus, number>,
    [ordersByStatus],
  )

  // 탭별 표시 순서 — 승인대기·진행 탭만 먼저 들어온 주문이 위로 온다. 근거는 orderedForTab 주석 참고
  const visibleOrders = useMemo(
    () => orderedForTab(activeStatus, ordersByStatus[activeStatus]),
    [ordersByStatus, activeStatus],
  )

  // O15 호출 확인 — 성공하면 서버 calls에서 빠진다. 폴링을 기다리지 않고 바로 지운다(멱등이라 중복 눌림도 안전)
  const acknowledgeCall = async (callId: number) => {
    try {
      const res = await ackCall(callId)
      if (!res.ok && res.status !== 404) {
        setActionError(`호출을 확인 처리하지 못했어요 (${res.status})`)
        return
      }
      setCalls((prev) => prev.filter((c) => c.callId !== callId))
      setActionError(null)
    } catch {
      // 401이면 apiFetch가 이미 로그인 화면으로 보내는 중 — 그때는 문구를 덧그리지 않는다
      if (getAuthToken()) setActionError('호출을 확인 처리하지 못했어요. 네트워크 상태를 확인해주세요.')
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
        // 409 = 다른 기기(같은 부스 운영자)가 먼저 바꿨거나, 응답을 못 받은 앞선 요청이 이미 처리된 경우다.
        // 숫자만 보여주면 운영자가 다시 누르며 헤맨다 — 최신 목록을 불러와 실제 상태를 보여준다
        if (res.status === 409) {
          // 재조회로 실제 상태를 먼저 맞춘 뒤 알린다
          await refetchAll()
          setActionError(`${failMessage} — 이미 다른 상태로 바뀌었어요. 최신 목록을 불러왔어요.`)
          return
        }
        const body: { error?: { message?: string } } | null = await res.json().catch(() => null)
        setActionError(`${failMessage} (${body?.error?.message ?? res.status})`)
        return
      }
      setActionError(null)
      await refetchAll()
    } catch {
      if (!getAuthToken()) return
      // 요청은 서버에 닿았는데 응답만 잃었을 수 있다 — 화면을 실제 상태로 맞춘 뒤 알린다(재조회도 실패하면 끊김 배너가 뜬다)
      await refetchAll()
      setActionError(`${failMessage} — 응답을 받지 못했어요. 처리됐을 수 있으니 목록을 확인해 주세요.`)
    } finally {
      pendingRef.current.delete(orderId)
      setPendingOrderIds(new Set(pendingRef.current))
    }
  }

  // O28 승인 — 완료 처리와 마찬가지로 되돌릴 방법(주문 자체를 취소)이 있으니 확인을 묻지 않는다
  const approveOrder = (orderId: number) =>
    runOrderAction(orderId, () => approveOrderRequest(orderId), '주문을 승인 처리하지 못했어요')

  // 거절은 손님에게 바로 영향이 가는 취소와 같은 무게라 취소와 같은 방식으로 한 번 더 묻는다.
  // 별도 API 없이 기존 취소(O13)를 사유만 다르게 재사용한다(백엔드 DashboardOrderActionService.approve 주석 참조)
  const rejectOrder = (orderId: number) => {
    if (!window.confirm('이 주문을 거절할까요?')) return
    return runOrderAction(orderId, () => cancelOrderRequest(orderId, '주문 거절'), '주문을 거절 처리하지 못했어요')
  }

  const completeOrder = (orderId: number) =>
    runOrderAction(orderId, () => completeOrderRequest(orderId), '주문을 완료 처리하지 못했어요')

  // 취소는 손님에게 바로 영향이 가고 되돌리려면 한 단계를 더 거쳐야 한다 — 한 번 더 묻는다(되돌리기와 같은 방식)
  const cancelOrder = (orderId: number) => {
    if (!window.confirm('이 주문을 취소할까요?')) return
    return runOrderAction(orderId, () => cancelOrderRequest(orderId), '주문을 취소 처리하지 못했어요')
  }

  // 되돌리기 — 완료·취소된 주문을 진행(RECEIVED)으로 되돌린다. 결제/환불 상태는 건드리지 않는다
  const restoreOrder = (orderId: number) => {
    if (!window.confirm('이 주문을 진행 상태로 복구할까요?')) return
    return runOrderAction(orderId, () => restoreOrderRequest(orderId), '주문을 복구하지 못했어요')
  }

  // 환불 완료 — 실제로 돈을 돌려준 뒤 누르는 버튼이라 한 번 더 묻는다. 되돌릴 수 없다.
  const refundDone = (orderId: number) => {
    if (!window.confirm('환불을 완료 처리할까요? 되돌릴 수 없어요.')) return
    return runOrderAction(orderId, () => refundDoneRequest(orderId), '환불 완료 처리를 하지 못했어요')
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

      {actionError && (
        <p className="flex items-center gap-3 px-10 pt-4 text-sm text-red-600">
          {actionError}
          <button type="button" className="underline" onClick={() => setActionError(null)}>
            닫기
          </button>
        </p>
      )}
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
            onApprove={approveOrder}
            onReject={rejectOrder}
            onComplete={completeOrder}
            onCancel={cancelOrder}
            onRestore={restoreOrder}
            onRefundDone={isAdmin ? refundDone : undefined}
          />
        ))}
        {visibleOrders.length === 0 && !error && (
          <p className="col-span-full py-20 text-center text-neutral-400">해당하는 주문이 없어요.</p>
        )}
      </div>
    </div>
  )
}
