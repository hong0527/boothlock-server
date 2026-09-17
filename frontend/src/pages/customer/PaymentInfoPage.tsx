import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import BackButton from '../../components/customer/BackButton'
import { CopyIcon, InfoIcon } from '../../components/customer/icons'
import { CUSTOMER_BUTTON_BASE } from '../../components/controlStyles'
import { customerApiFetch } from '../../lib/customerApiFetch'
import { getSessionInfo } from '../../lib/customerSession'
import type { OrderSummary } from '../../types/customer'

export default function PaymentInfoPage() {
  const navigate = useNavigate()
  const sessionInfo = getSessionInfo()
  const [orders, setOrders] = useState<OrderSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    customerApiFetch('/api/v1/orders')
      .then((res) => {
        if (!res.ok) throw new Error(`주문 정보를 불러오지 못했어요 (${res.status})`)
        return res.json() as Promise<{ orders: OrderSummary[] }>
      })
      .then((data) => setOrders(data.orders))
      .catch((err) => setError(err instanceof Error ? err.message : '주문 정보를 불러오지 못했어요.'))
  }, [])

  if (error) {
    return (
      <div className="flex min-h-screen w-full items-center justify-center bg-neutral-50">
        <p className="text-body-1 text-red-600">{error}</p>
      </div>
    )
  }

  if (!orders) {
    return (
      <div className="flex min-h-screen w-full items-center justify-center bg-neutral-50">
        <p className="text-body-1 text-neutral-400">주문 정보를 불러오는 중...</p>
      </div>
    )
  }

  // 미결제 = 서빙 여부와 무관하게 입금이 안 된 주문 — 취소된 주문(CANCELED+UNPAID)은 받을 돈이 없어 제외
  // (lib/sessionOrders.ts의 isUnpaid와 같은 정의 — 백엔드 UnpaidOrderRule 기준)
  const unpaidOrders = orders.filter(
    (order) => (order.status === 'RECEIVED' || order.status === 'DONE') && order.paymentStatus === 'UNPAID',
  )
  const totalAmount = unpaidOrders.reduce((sum, order) => sum + order.totalAmount, 0)
  const bankAccount = unpaidOrders[0]?.payment.bankAccount ?? orders[0]?.payment.bankAccount ?? ''
  const tableLabel = sessionInfo?.tableLabel ?? ''
  const depositorNameRule = `입금자명을 '이름+${tableLabel}'로 입력해주세요 (예: 김철수${tableLabel})`

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(bankAccount)
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
        {unpaidOrders.length === 0 ? (
          <p className="pt-20 text-center text-body-1 text-neutral-400">미결제 주문이 없어요.</p>
        ) : (
          <>
            <div className="mt-8 rounded-[10px] border border-[#b8dcd3] bg-primary-50 p-5">
              <p className="text-body-2 text-neutral-900">입금 계좌</p>
              <div className="mt-3 flex items-start gap-1 text-body-2 text-neutral-900">
                <span className="mt-1">•</span>
                <div className="flex flex-1 items-center gap-2">
                  <span className="underline">{bankAccount}</span>
                  <button type="button" onClick={handleCopy} aria-label="계좌번호 복사" className="text-neutral-900">
                    <CopyIcon className="h-[19px] w-[19px]" />
                  </button>
                </div>
              </div>
              <p className="mt-1 pl-3 text-body-2 text-neutral-900">{depositorNameRule}</p>
              <div className="mt-3 flex items-center justify-between border-t border-neutral-100 pt-3 text-body-2 text-neutral-900">
                <span>미결제 주문 {unpaidOrders.length}건 합계</span>
                <span>{totalAmount.toLocaleString()}원</span>
              </div>
            </div>

            <div className="mt-3 flex items-center gap-2 rounded-[10px] bg-neutral-100 px-4 py-3">
              <InfoIcon className="h-[18px] w-[18px] text-neutral-700" />
              <p className="text-caption text-neutral-700">결제 후에는 취소가 어려워요.</p>
            </div>

            {copied && <p className="mt-2 text-center text-caption text-neutral-400">복사됐어요.</p>}
          </>
        )}
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