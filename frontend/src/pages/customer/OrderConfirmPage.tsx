import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import BackButton from '../../components/customer/BackButton'
import { CUSTOMER_BUTTON_BASE } from '../../components/controlStyles'
import { useCart } from '../../context/CartContext'
import { customerApiFetch } from '../../lib/customerApiFetch'
import { getSessionInfo, getSessionToken } from '../../lib/customerSession'
import { customerOrderFingerprint, customerOrderKeys } from '../../lib/idempotencyKey'
import { TimeoutError } from '../../lib/fetchWithTimeout'
import { pendingSeatFee, SEAT_FEE_PER_PERSON } from '../../lib/seatFee'
import { displayTableLabel } from '../../lib/tableLabel'
import type { OrderSummary } from '../../types/customer'

type OrderErrorBody = { error: { code: string; message: string } }

export default function OrderConfirmPage() {
  const navigate = useNavigate()
  const sessionInfo = getSessionInfo()
  const { items, totalAmount, clear } = useCart()
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  // 자릿세 미리보기용 — 이 세션에 이미 청구된 자릿세가 있는지 주문내역(C4)으로 확인한다. 못 불러오면 null(안내 문구로 대신)
  const [myOrders, setMyOrders] = useState<OrderSummary[] | null>(null)
  const partySize = sessionInfo?.partySize

  useEffect(() => {
    customerApiFetch('/api/v1/orders')
      .then((res) => (res.ok ? res.json() : null))
      .then((data: { orders: OrderSummary[] } | null) => setMyOrders(data?.orders ?? null))
      .catch(() => setMyOrders(null))
  }, [])

  const seatFee = pendingSeatFee(partySize, myOrders)

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
        else if (code === 'PARTY_SIZE_REQUIRED') {
          // 인원 선택을 건너뛰고 들어온 세션(두 번째 폰·재스캔) — 인원을 고르고 이 화면으로 돌아온다(장바구니는 그대로)
          navigate('/party-size', { state: { returnTo: '/order-confirm' } })
          return
        }
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
          {seatFee !== null && seatFee > 0 && (
            <div className="flex items-center justify-between border-t border-neutral-100 py-3 text-body-1 text-neutral-900">
              <span>
                자릿세 {partySize}명 × {SEAT_FEE_PER_PERSON.toLocaleString()}원
              </span>
              <span>{seatFee.toLocaleString()}원</span>
            </div>
          )}
          <div className="flex items-center justify-between border-t border-neutral-100 pt-3 text-body-2 text-neutral-900">
            <span>주문 금액</span>
            <span>{(totalAmount + (seatFee ?? 0)).toLocaleString()}원</span>
          </div>
          {seatFee === null && (
            <p className="pt-2 text-body-3 text-neutral-500">
              첫 주문에는 자릿세(1인 {SEAT_FEE_PER_PERSON.toLocaleString()}원)가 함께 청구돼요.
            </p>
          )}
        </div>

        {error && <p className="mt-4 text-body-3 text-red-600">{error}</p>}
      </div>

      <div className="px-6 pb-8">
        <p className="mb-3 text-center text-body-3 text-neutral-400">
          결제는 계좌이체로만 진행돼요. 신중하게 주문해주세요.
        </p>
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