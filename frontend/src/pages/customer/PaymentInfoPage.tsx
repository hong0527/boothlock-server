import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { CONTROL_BASE } from '../../components/controlStyles'
import PrimaryButton from '../../components/PrimaryButton'
import { customerApiFetch } from '../../lib/customerApiFetch'
import type { OrderCreateResult, OrderSummary } from '../../types/customer'

type LocationState = { order?: OrderCreateResult } | null

export default function PaymentInfoPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const stateOrder = (location.state as LocationState)?.order
  const [order, setOrder] = useState<OrderCreateResult | OrderSummary | null>(stateOrder ?? null)
  const [copied, setCopied] = useState(false)

  // 새로고침 등으로 화면 state가 사라졌으면 내 주문 중 가장 최근 것으로 대체한다 (C4)
  useEffect(() => {
    if (order) return
    customerApiFetch('/api/v1/orders')
      .then((res) => (res.ok ? res.json() : null))
      .then((data: { orders: OrderSummary[] } | null) => {
        if (data?.orders?.[0]) setOrder(data.orders[0])
      })
      .catch(() => {})
  }, [order])

  if (!order) {
    return (
      <div className="flex min-h-screen w-full items-center justify-center bg-white">
        <p className="text-body-1 text-neutral-400">주문 정보를 불러오는 중...</p>
      </div>
    )
  }

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(order.payment.bankAccount)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="flex min-h-screen w-full flex-col bg-white">
      <div className="border-b border-neutral-100 px-5 py-4">
        <h1 className="text-heading-3 text-neutral-900">결제 안내</h1>
      </div>

      <div className="flex-1 px-5 py-8 text-center">
        <p className="text-heading-3 text-neutral-900">
          아래 계좌로
          <br />
          주문 금액을 입금해주세요.
        </p>
        <p className="mt-2 text-body-3 text-neutral-400">입금이 확인되면 조리가 시작됩니다.</p>

        <div className="mt-6 rounded-2xl border border-neutral-100 bg-neutral-50 p-5 text-left">
          <p className="text-body-3 text-neutral-400">입금 계좌</p>
          <div className="mt-2 flex items-center justify-between">
            <span className="text-body-1 text-neutral-900">{order.payment.bankAccount}</span>
            <button type="button" onClick={handleCopy} className="text-body-3 text-primary-500 underline">
              {copied ? '복사됨' : '복사'}
            </button>
          </div>
          <p className="mt-2 text-body-3 text-neutral-400">{order.payment.depositorNameRule}</p>
          <div className="mt-4 flex items-center justify-between border-t border-neutral-100 pt-4 text-heading-3 text-neutral-900">
            <span>주문 금액</span>
            <span>{order.totalAmount.toLocaleString()}원</span>
          </div>
        </div>

        <p className="mt-4 text-body-3 text-neutral-400">결제 후에는 취소가 어려워요.</p>
      </div>

      <div className="flex flex-col gap-3 px-5 py-5">
        <button
          type="button"
          onClick={() => navigate('/order-history', { replace: true })}
          className={`${CONTROL_BASE} border border-neutral-900 text-heading-3 text-neutral-900`}
        >
          주문내역 확인
        </button>
        <PrimaryButton type="button" onClick={() => navigate('/order', { replace: true })}>
          완료
        </PrimaryButton>
      </div>
    </div>
  )
}