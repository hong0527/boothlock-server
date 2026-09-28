import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import BackButton from '../../components/customer/BackButton'
import { PersonIcon } from '../../components/customer/icons'
import StaffCallConfirmModal from '../../components/customer/StaffCallConfirmModal'
import { customerApiFetch } from '../../lib/customerApiFetch'
import { createPollGuard } from '../../lib/pollGuard'
import { customerOrderBadge, type CustomerOrderBadge } from '../../lib/customerOrderStatus'
import { getSessionInfo } from '../../lib/customerSession'
import { displayTableLabel } from '../../lib/tableLabel'
import { formatClockTime } from '../../lib/time'
import { useStaffCallModal } from '../../lib/useStaffCallModal'
import type { OrderSummary } from '../../types/customer'
import { onResume } from '../../lib/onResume'

const POLL_INTERVAL_MS = 7000

// 승인대기는 조리가 아직 시작되지 않았다는 신호라 눈에 띄게, 취소·거절은 흐리게
const BADGE_CLASS: Record<CustomerOrderBadge['tone'], string> = {
  pending: 'bg-amber-100 text-amber-800',
  active: 'bg-primary-100 text-white',
  done: 'bg-neutral-200 text-neutral-700',
  canceled: 'bg-neutral-100 text-neutral-400',
}

export default function OrderHistoryPage() {
  const navigate = useNavigate()
  const sessionInfo = getSessionInfo()
  const [orders, setOrders] = useState<OrderSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const { callMessage, showCallConfirm, openCallConfirm, closeCallConfirm, handleCallStaff } = useStaffCallModal('HELP')

  const pollGuard = useRef(createPollGuard())

  const fetchOrders = useCallback(async (skipIfBusy = false) => {
    const runId = pollGuard.current.begin(skipIfBusy)
    if (runId === null) return
    try {
      const res = await customerApiFetch('/api/v1/orders')
      if (!res.ok) throw new Error(`주문내역을 불러오지 못했어요 (${res.status})`)
      const data: { orders: OrderSummary[] } = await res.json()
      if (!pollGuard.current.isLatest(runId)) return
      setOrders(data.orders)
      setError(null)
    } catch (err) {
      if (!pollGuard.current.isLatest(runId)) return
      setError(err instanceof Error ? err.message : '주문내역을 불러오지 못했어요.')
    } finally {
      pollGuard.current.end()
    }
  }, [])

  useEffect(() => {
    fetchOrders()
    const id = setInterval(() => fetchOrders(true), POLL_INTERVAL_MS)
    const offResume = onResume(() => fetchOrders(true))
    return () => {
      clearInterval(id)
      offResume()
    }
  }, [fetchOrders])

  return (
    <div className="flex min-h-screen w-full flex-col bg-neutral-50">
      <div className="flex h-[114px] items-center justify-between bg-primary-50 px-4">
        <div className="flex items-center">
          <BackButton />
          <h1 className="ml-3 text-heading-1 text-neutral-900">주문내역</h1>
        </div>
        <button
          type="button"
          onClick={openCallConfirm}
          className="flex flex-col items-center gap-0.5 px-2 text-[13px] leading-[1.2] font-medium tracking-[-0.52px] text-neutral-900"
        >
          <PersonIcon className="size-[24px]" />
          직원 호출
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-[18px] py-6">
        {error && <p className="pb-4 text-body-3 text-red-600">{error}</p>}
        {callMessage && <p className="pb-4 text-center text-body-3 text-neutral-400">{callMessage}</p>}

        <div className="flex flex-col gap-4">
          {orders.map((order) => {
            const badge = customerOrderBadge(order)
            return (
            <div key={order.orderId} className="rounded-[10px] border border-[#b8dcd3] bg-primary-50 p-5">
              <div className="flex items-start justify-between text-body-3 text-neutral-500">
                <div>
                  <p>테이블 번호: {sessionInfo?.tableLabel && displayTableLabel(sessionInfo.tableLabel)}</p>
                  <p>주문 시간: {formatClockTime(order.createdAt)}</p>
                </div>
                <div className="flex flex-col items-end gap-1">
                  <span className={`rounded-md px-2 py-0.5 text-caption font-semibold ${BADGE_CLASS[badge.tone]}`}>
                    {badge.label}
                  </span>
                  {badge.refundNote && <span className="text-caption text-neutral-500">{badge.refundNote}</span>}
                </div>
              </div>

              <div className="mt-3 divide-y divide-neutral-100 border-t border-neutral-100">
                {order.items.map((item, idx) => (
                  <div
                    key={item.menuId ?? `${item.itemType}-${idx}`}
                    className="flex items-center justify-between py-2 text-body-1 text-neutral-900"
                  >
                    <span>{item.menuName}</span>
                    {/* 자릿세는 인원수라 "N명"으로 — 운영자 카드(OrderCard)와 같은 표기 */}
                    <span>{item.itemType === 'SEAT_FEE' ? `${item.qty}명` : `${item.qty}개`}</span>
                  </div>
                ))}
              </div>

              <div className="flex items-center justify-between border-t border-neutral-100 pt-3 text-body-2 text-neutral-900">
                <span>주문 금액</span>
                <span>{order.totalAmount.toLocaleString()}원</span>
              </div>
            </div>
            )
          })}
        </div>

        {orders.length === 0 && !error && (
          <p className="py-20 text-center text-body-1 text-neutral-400">주문 내역이 없어요.</p>
        )}
      </div>

      <div className="flex flex-col gap-[6px] px-[18px] pb-8">
        <button
          type="button"
          onClick={() => navigate('/payment-info')}
          className="h-[50px] w-full rounded-[12px] border border-neutral-900 text-body-1 text-neutral-900"
        >
          결제 안내
        </button>
        <button
          type="button"
          onClick={() => navigate('/order')}
          className="h-[50px] w-full rounded-[12px] bg-primary-300 text-body-1 text-white"
        >
          확인
        </button>
      </div>

      {showCallConfirm && (
        <StaffCallConfirmModal onConfirm={handleCallStaff} onCancel={closeCallConfirm} />
      )}
    </div>
  )
}