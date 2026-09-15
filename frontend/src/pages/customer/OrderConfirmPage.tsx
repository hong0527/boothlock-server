import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { CONTROL_BASE } from '../../components/controlStyles'
import { useCart } from '../../context/CartContext'
import { customerApiFetch } from '../../lib/customerApiFetch'
import { getSessionInfo } from '../../lib/customerSession'
import type { OrderCreateResult } from '../../types/customer'

type OrderErrorBody = { error: { code: string; message: string } }

export default function OrderConfirmPage() {
  const navigate = useNavigate()
  const sessionInfo = getSessionInfo()
  const { items, totalAmount, clear } = useCart()
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const handleSubmit = async () => {
    if (loading || items.length === 0) return
    setLoading(true)
    setError(null)

    try {
      const res = await customerApiFetch('/api/v1/orders', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': crypto.randomUUID(),
        },
        body: JSON.stringify({ items: items.map((item) => ({ menuId: item.menuId, qty: item.qty })) }),
      })

      if (!res.ok) {
        const body: OrderErrorBody | null = await res.json().catch(() => null)
        const code = body?.error?.code
        if (code === 'SOLD_OUT') setError('품절된 메뉴가 포함되어 있어요. 장바구니를 다시 확인해주세요.')
        else if (code === 'ORDER_RATE_LIMITED') setError('미결제 주문이 많습니다. 입금 확인 후 추가 주문해주세요.')
        else if (code === 'ORDER_CLOSED') setError('지금은 주문 접수 시간이 아니에요.')
        else setError('주문에 실패했어요. 잠시 후 다시 시도해주세요.')
        return
      }

      const order: OrderCreateResult = await res.json()
      clear()
      navigate('/payment-info', { state: { order }, replace: true })
    } catch {
      setError('서버에 연결할 수 없어요. 네트워크 상태를 확인해주세요.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen w-full flex-col bg-neutral-50">
      <div className="flex h-[114px] items-center bg-primary-50 px-4">
        <button
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로가기"
          className="flex h-8 w-8 items-center justify-center text-neutral-900"
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M15 5l-7 7 7 7" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        <h1 className="ml-3 text-heading-1 text-neutral-900">주문 확인</h1>
      </div>

      <div className="flex-1 px-[18px] py-6">
        <div className="rounded-[10px] border border-[#b8dcd3] bg-primary-50 p-6">
          <p className="text-body-3 text-neutral-500">테이블 번호: {sessionInfo?.tableLabel}</p>
          <div className="mt-3 divide-y divide-neutral-100 border-t border-neutral-100">
            {items.map((item) => (
              <div key={item.menuId} className="flex items-center justify-between py-3 text-body-1 text-neutral-900">
                <span>{item.name}</span>
                <span>{item.qty}개</span>
              </div>
            ))}
          </div>
          <div className="flex items-center justify-between border-t border-neutral-100 pt-3 text-body-2 text-neutral-900">
            <span>주문 금액</span>
            <span>{totalAmount.toLocaleString()}원</span>
          </div>
        </div>

        {error && <p className="mt-4 text-body-3 text-red-600">{error}</p>}
      </div>

      <div className="px-6 pb-8">
        <button
          type="button"
          onClick={handleSubmit}
          disabled={loading || items.length === 0}
          className={`${CONTROL_BASE} bg-black text-heading-3 text-white disabled:opacity-40`}
        >
          {loading ? '주문 중...' : '주문하기'}
        </button>
      </div>
    </div>
  )
}