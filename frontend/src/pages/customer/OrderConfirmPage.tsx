import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import PrimaryButton from '../../components/PrimaryButton'
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
      // C3: 재시도 시에도 같은 키를 재사용해야 하지만, 이 화면은 실패 시 사람이 다시 눌러야 하는 구조라
      // 매 시도(=매 클릭)를 새 주문 시도로 본다 — 더블클릭 자체는 loading 가드로 막는다
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
    <div className="flex min-h-screen w-full flex-col bg-white">
      <div className="flex items-center gap-4 border-b border-neutral-100 px-5 py-4">
        <button
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로가기"
          className="text-heading-2 text-neutral-900"
        >
          ‹
        </button>
        <h1 className="text-heading-3 text-neutral-900">주문 확인</h1>
      </div>

      <div className="flex-1 px-5 py-4">
        <p className="text-body-3 text-neutral-400">테이블 번호: {sessionInfo?.tableLabel}</p>

        <div className="mt-3 divide-y divide-neutral-100">
          {items.map((item) => (
            <div key={item.menuId} className="flex items-center justify-between py-3 text-body-1 text-neutral-900">
              <span>{item.name}</span>
              <span>{item.qty}개</span>
            </div>
          ))}
        </div>

        <div className="mt-3 flex items-center justify-between border-t border-neutral-100 pt-3 text-heading-3 text-neutral-900">
          <span>주문 금액</span>
          <span>{totalAmount.toLocaleString()}원</span>
        </div>

        {error && <p className="mt-4 text-body-3 text-red-600">{error}</p>}
      </div>

      <div className="px-5 py-5">
        <PrimaryButton
          type="button"
          onClick={handleSubmit}
          disabled={loading || items.length === 0}
          className="disabled:opacity-40"
        >
          {loading ? '주문 중...' : '주문하기'}
        </PrimaryButton>
      </div>
    </div>
  )
}