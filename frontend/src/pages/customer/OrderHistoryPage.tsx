import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import PrimaryButton from '../../components/PrimaryButton'
import { customerApiFetch } from '../../lib/customerApiFetch'
import { getSessionInfo } from '../../lib/customerSession'
import { formatClockTime } from '../../lib/time'
import type { OrderSummary } from '../../types/customer'

const STATUS_LABEL: Record<OrderSummary['status'], string> = {
  RECEIVED: '접수됨',
  DONE: '완료',
  CANCELED: '취소됨',
}

const POLL_INTERVAL_MS = 7000 // 명세서 C4 권장 폴링 주기(5~10초)

export default function OrderHistoryPage() {
  const navigate = useNavigate()
  const sessionInfo = getSessionInfo()
  const [orders, setOrders] = useState<OrderSummary[]>([])
  const [error, setError] = useState<string | null>(null)

  const fetchOrders = useCallback(async () => {
    try {
      const res = await customerApiFetch('/api/v1/orders')
      if (!res.ok) throw new Error(`주문내역을 불러오지 못했어요 (${res.status})`)
      const data: { orders: OrderSummary[] } = await res.json()
      setOrders(data.orders)
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : '주문내역을 불러오지 못했어요.')
    }
  }, [])

  useEffect(() => {
    fetchOrders()
    const id = setInterval(fetchOrders, POLL_INTERVAL_MS)
    return () => clearInterval(id)
  }, [fetchOrders])

  const cancelOrder = async (orderId: number) => {
    const res = await customerApiFetch(`/api/v1/orders/${orderId}/cancel`, { method: 'POST' })
    if (!res.ok) {
      setError('주문을 취소하지 못했어요. 직원에게 요청해주세요.')
      return
    }
    fetchOrders()
  }

  return (
    <div className="flex min-h-screen w-full flex-col bg-white">
      <div className="border-b border-neutral-100 px-5 py-4">
        <h1 className="text-heading-3 text-neutral-900">주문내역</h1>
      </div>

      <div className="flex-1 overflow-y-auto px-5 py-4">
        {error && <p className="pb-4 text-body-3 text-red-600">{error}</p>}

        {orders.map((order) => (
          <div key={order.orderId} className="mb-4 rounded-2xl border border-neutral-100 p-5">
            <div className="flex items-center justify-between">
              <p className="text-body-3 text-neutral-400">
                테이블 번호: {sessionInfo?.tableLabel}
                <br />
                주문 시간: {formatClockTime(order.createdAt)}
              </p>
              <span className="text-body-3 text-neutral-400">{STATUS_LABEL[order.status]}</span>
            </div>

            <div className="mt-3 divide-y divide-neutral-100">
              {order.items.map((item) => (
                <div
                  key={item.menuId}
                  className="flex items-center justify-between py-2 text-body-1 text-neutral-900"
                >
                  <span>{item.menuName}</span>
                  <span>{item.qty}개</span>
                </div>
              ))}
            </div>

            <div className="mt-3 flex items-center justify-between border-t border-neutral-100 pt-3 text-heading-3 text-neutral-900">
              <span>주문 금액</span>
              <span>{order.totalAmount.toLocaleString()}원</span>
            </div>

            {order.canCancel && (
              <button
                type="button"
                onClick={() => cancelOrder(order.orderId)}
                className="mt-3 w-full rounded-xl border border-neutral-900 py-2 text-body-2 text-neutral-900"
              >
                주문 취소
              </button>
            )}
          </div>
        ))}

        {orders.length === 0 && !error && (
          <p className="py-20 text-center text-body-1 text-neutral-400">주문 내역이 없어요.</p>
        )}
      </div>

      <div className="px-5 py-5">
        <PrimaryButton type="button" onClick={() => navigate('/order')}>
          확인
        </PrimaryButton>
      </div>
    </div>
  )
}