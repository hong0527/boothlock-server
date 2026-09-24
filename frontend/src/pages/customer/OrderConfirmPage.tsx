import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import BackButton from '../../components/customer/BackButton'
import { CUSTOMER_BUTTON_BASE } from '../../components/controlStyles'
import { useCart } from '../../context/CartContext'
import { customerApiFetch } from '../../lib/customerApiFetch'
import { getSessionInfo, getSessionToken } from '../../lib/customerSession'
import { customerOrderFingerprint, customerOrderKeys } from '../../lib/idempotencyKey'
import { TimeoutError } from '../../lib/fetchWithTimeout'
import { displayTableLabel } from '../../lib/tableLabel'

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
          // 멱등키는 클릭·화면마다 새로 만들지 않는다(customerOrderKeys 참조) — 재시도는 같은 키라 서버가 중복 주문을 만들지 않는다
          'Idempotency-Key': customerOrderKeys.keyFor(customerOrderFingerprint(getSessionToken(), items)),
        },
        body: JSON.stringify({ items: items.map((item) => ({ menuId: item.menuId, qty: item.qty })) }),
      })

      if (!res.ok) {
        const body: OrderErrorBody | null = await res.json().catch(() => null)
        const code = body?.error?.code
        if (code === 'SOLD_OUT') setError('품절된 메뉴가 포함되어 있어요. 장바구니를 다시 확인해주세요.')
        else if (code === 'ORDER_RATE_LIMITED') setError('미결제 주문이 많습니다. 입금 확인 후 추가 주문해주세요.')
        else if (code === 'ORDER_CLOSED') setError('지금은 주문 접수 시간이 아니에요.')
        else if (res.status === 400) {
          // 키가 다른 세션 주문과 겹친 경우 서버가 "키를 새로" 달라고 한다 — 버려야 다음 클릭이 통과한다
          customerOrderKeys.clear()
          setError('주문에 실패했어요. 다시 눌러주세요.')
        } else setError('주문에 실패했어요. 잠시 후 다시 시도해주세요.')
        return
      }

      await res.json()
      customerOrderKeys.clear()
      clear()
      navigate('/order-history', { replace: true })
    } catch (e) {
      // 410(퇴실·만료)으로 customerApiFetch가 세션을 지우고 이동 중이면 네트워크 오류 문구가 잠깐 비치지 않게 한다
      if (!getSessionToken()) return
      // 응답만 못 받았을 수 있다(주문은 들어감) — 다시 눌러도 같은 키라 두 번 들어가지 않는다는 걸 알려 불안한 연타·재주문을 막는다
      setError(
        e instanceof TimeoutError
          ? '응답이 늦어요. 다시 눌러도 주문은 한 번만 들어가요.'
          : '서버에 연결할 수 없어요. 다시 눌러도 주문은 한 번만 들어가요.',
      )
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen w-full flex-col bg-neutral-50">
      <div className="flex h-[114px] items-center bg-primary-50 px-4">
        <BackButton />
        <h1 className="ml-3 text-heading-1 text-neutral-900">주문 확인</h1>
      </div>

      <div className="flex-1 px-[18px] py-6">
        <div className="rounded-[10px] border border-[#b8dcd3] bg-primary-50 p-6">
          <p className="text-body-3 text-neutral-500">
            테이블 번호: {sessionInfo?.tableLabel && displayTableLabel(sessionInfo.tableLabel)}
          </p>
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
          className={`${CUSTOMER_BUTTON_BASE} bg-primary-300 text-heading-3 text-white disabled:opacity-40`}
        >
          {loading ? '주문 중...' : '주문하기'}
        </button>
      </div>
    </div>
  )
}