import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import BackButton from '../../components/customer/BackButton'
import { CopyIcon, InfoIcon } from '../../components/customer/icons'
import { CUSTOMER_BUTTON_BASE } from '../../components/controlStyles'
import { customerApiFetch } from '../../lib/customerApiFetch'
import type { OrderCreateResult, OrderSummary } from '../../types/customer'

type LocationState = { order?: OrderCreateResult } | null

export default function PaymentInfoPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const stateOrder = (location.state as LocationState)?.order
  const [order, setOrder] = useState<OrderCreateResult | OrderSummary | null>(stateOrder ?? null)
  const [copied, setCopied] = useState(false)

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
      <div className="flex min-h-screen w-full items-center justify-center bg-neutral-50">
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
    <div className="flex min-h-screen w-full flex-col bg-neutral-50">
      <div className="flex h-[114px] items-center bg-primary-50 px-4">
        <BackButton />
        <h1 className="ml-3 text-heading-1 text-neutral-900">결제 안내</h1>
      </div>

      <div className="flex-1 px-[18px] py-8">
        <p className="text-center text-heading-2 text-neutral-900">
          아래 계좌로
          <br />
          주문 금액을 입금해주세요.
        </p>
        <p className="mt-2 text-center text-body-2 text-neutral-400">입금이 확인되면 조리가 시작됩니다.</p>

        <div className="mt-8 rounded-[10px] border border-[#b8dcd3] bg-primary-50 p-5">
          <p className="text-body-2 text-neutral-900">입금 계좌</p>
          <div className="mt-3 flex items-start gap-1 text-body-2 text-neutral-900">
            <span className="mt-1">•</span>
            <div className="flex flex-1 items-center gap-2">
              <span className="underline">{order.payment.bankAccount}</span>
              <button type="button" onClick={handleCopy} aria-label="계좌번호 복사" className="text-neutral-900">
                <CopyIcon className="h-[19px] w-[19px]" />
              </button>
            </div>
          </div>
          <p className="mt-1 pl-3 text-body-2 text-neutral-900">{order.payment.depositorNameRule}</p>
          <div className="mt-3 flex items-center justify-between border-t border-neutral-100 pt-3 text-body-2 text-neutral-900">
            <span>주문 금액</span>
            <span>{order.totalAmount.toLocaleString()}원</span>
          </div>
        </div>

        <div className="mt-3 flex items-center gap-2 rounded-[10px] bg-neutral-100 px-4 py-3">
          <InfoIcon className="h-[18px] w-[18px] text-neutral-700" />
          <p className="text-caption text-neutral-700">결제 후에는 취소가 어려워요.</p>
        </div>

        {copied && <p className="mt-2 text-center text-caption text-neutral-400">복사됐어요.</p>}
      </div>

      <div className="flex flex-col gap-[6px] px-[18px] pb-8">
        <button
          type="button"
          onClick={() => navigate('/order-history', { replace: true })}
          className={`${CUSTOMER_BUTTON_BASE} bg-black text-heading-3 text-white`}
        >
          주문내역 확인
        </button>
        <button
          type="button"
          onClick={() => navigate('/order', { replace: true })}
          className={`${CUSTOMER_BUTTON_BASE} bg-black text-heading-3 text-white`}
        >
          완료
        </button>
      </div>
    </div>
  )
}