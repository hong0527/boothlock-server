import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import BackButton from '../../components/customer/BackButton'
import { PersonIcon } from '../../components/customer/icons'
import StaffCallConfirmModal from '../../components/customer/StaffCallConfirmModal'
import { CUSTOMER_BUTTON_BASE } from '../../components/controlStyles'
import { useCart } from '../../context/CartContext'
import { assetUrl } from '../../lib/apiBase'
import { customerApiFetch } from '../../lib/customerApiFetch'
import { getSessionInfo, getSessionToken } from '../../lib/customerSession'
import { customerOrderFingerprint, customerOrderKeys } from '../../lib/idempotencyKey'
import { TimeoutError } from '../../lib/fetchWithTimeout'
import { minOrderShortfall } from '../../lib/minOrder'
import { DEFAULT_SEAT_FEE_PER_PERSON, pendingSeatFee } from '../../lib/seatFee'
import { useStaffCallModal } from '../../lib/useStaffCallModal'
import type { OrderSummary } from '../../types/customer'

type OrderErrorBody = { error: { code: string; message: string } }

// 2026-09-28 팀 결정 — 장바구니 다음의 별도 "주문 확인" 화면을 없애고 장바구니에서 바로 주문한다
// (이전엔 CartPage "주문하기" → OrderConfirmPage에서 한 번 더 "주문하기"를 눌러야 실제로 접수됐다).
// 자릿세 미리보기·"계좌이체만" 안내 문구는 이 화면으로 그대로 옮겨왔다 — 정보 자체가 없어진 게 아니라
// 한 탭에서 다 보여주고 한 번만 누르면 되게 합친 것뿐이다.
export default function CartPage() {
  const navigate = useNavigate()
  const { items, totalAmount, updateQty, removeItem, clear } = useCart()
  const sessionInfo = getSessionInfo()
  const partySize = sessionInfo?.partySize
  const seatFeePerPerson = sessionInfo?.seatFeePerPerson ?? DEFAULT_SEAT_FEE_PER_PERSON
  const { callMessage, showCallConfirm, openCallConfirm, closeCallConfirm, handleCallStaff } = useStaffCallModal('HELP')

  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  // 자릿세는 이 세션 첫 주문 때 서버가 별도 주문으로 함께 만든다 — 누르기 전에 금액을 보여 주려고 주문내역(C4)으로
  // 이미 청구됐는지 본다. undefined = 확인 중(주문 버튼을 잠깐 막는다), null = 못 불러옴(안내 문구로 대신하고 막지 않는다)
  const [myOrders, setMyOrders] = useState<OrderSummary[] | null | undefined>(undefined)

  useEffect(() => {
    customerApiFetch('/api/v1/orders')
      .then((res) => (res.ok ? res.json() : null))
      .then((data: { orders: OrderSummary[] } | null) => setMyOrders(data?.orders ?? null))
      .catch(() => setMyOrders(null))
  }, [])

  // 장바구니를 고치면 직전 실패 문구를 지운다 — 타임아웃 문구가 "다시 눌러도 주문은 한 번만 들어가요"라고
  // 약속하는데, 멱등키는 담긴 메뉴로 만들어지므로(customerOrderFingerprint) 수량을 바꾸면 키가 달라져
  // 그 약속이 깨진다. 서버에 이미 들어간 주문이 있으면 두 건이 된다. 문구가 장바구니보다 오래 살지 않게 한다
  const cartSignature = items.map((item) => `${item.menuId}x${item.qty}`).join('|')
  useEffect(() => {
    setError(null)
  }, [cartSignature])

  // 자릿세를 물릴 수 없는 부스·세션이면 미리보기가 무의미하다 — 그런데도 주문 버튼을 GET 응답까지(최대 10초)
  // 막으면 혼잡한 축제장 회선에서 눌러도 아무 반응이 없다. 자릿세가 걸릴 때만 기다린다
  const seatFeePossible = !!partySize && partySize > 0 && seatFeePerPerson > 0 && sessionInfo?.seatFeeCharged !== true
  const minOrderAmount = sessionInfo?.minOrderAmount ?? 0
  // 최소주문금액도 주문내역(C4)을 봐야 첫 주문인지 안다 — 걸리는 부스일 때만 응답을 기다린다(자릿세와 같은 이유)
  const previewLoading = myOrders === undefined && (seatFeePossible || minOrderAmount > 0)
  const shortfall = minOrderShortfall(minOrderAmount, totalAmount, myOrders)
  const seatFee =
    !seatFeePossible || myOrders === undefined
      ? 0
      : pendingSeatFee(partySize, seatFeePerPerson, myOrders, sessionInfo?.seatFeeCharged === true)

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
        // 첫 주문 최소금액 미달 — 화면이 미리 막지만, QR을 찍은 뒤 운영자가 금액을 올렸으면 서버 문구(금액 포함)로 알린다
        else if (code === 'INVALID_STATE') setError(body?.error?.message ?? '주문 금액을 다시 확인해주세요.')
        else if (code === 'PARTY_SIZE_REQUIRED') {
          // 인원 선택을 건너뛰고 들어온 세션(두 번째 폰·재스캔) — 인원을 고르고 이 화면으로 돌아온다(장바구니는 그대로)
          // replace — 돌아왔을 때 뒤로가기가 이 화면을 한 번 더 보여주지 않게
          navigate('/party-size', { replace: true, state: { returnTo: '/cart' } })
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
      <div className="flex h-[114px] items-center justify-between bg-primary-50 px-4">
        <div className="flex items-center">
          <BackButton />
          <h1 className="ml-3 text-heading-1 text-neutral-900">장바구니</h1>
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

      <div className="flex-1 overflow-y-auto px-6">
        {items.map((item) => (
          <div key={item.menuId} className="flex items-start gap-[19px] border-b border-neutral-100 py-6">
            <div className="h-20 w-20 shrink-0 overflow-hidden rounded-[12px] bg-[#d9d9d9]">
              {item.imageUrl && (
                <img src={assetUrl(item.imageUrl)} alt="" loading="lazy" decoding="async" className="h-full w-full object-cover" />
              )}
            </div>
            <div className="flex flex-1 flex-col justify-between self-stretch">
              <div className="flex items-start justify-between">
                <span className="text-body-1 text-neutral-900">{item.name}</span>
                <button
                  type="button"
                  onClick={() => removeItem(item.menuId)}
                  aria-label={`${item.name} 삭제`}
                  className="text-neutral-900"
                >
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M6 6l12 12M18 6L6 18" strokeLinecap="round" />
                  </svg>
                </button>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-heading-3 text-neutral-700">{item.unitPrice.toLocaleString()}원</span>
                <div className="flex h-[31px] items-center gap-2 rounded-[20px] bg-neutral-200 px-3">
                  <button
                    type="button"
                    onClick={() => updateQty(item.menuId, item.qty - 1)}
                    aria-label="수량 줄이기"
                    className="text-neutral-900"
                  >
                    −
                  </button>
                  <span className="w-4 text-center text-body-2 text-neutral-900">{item.qty}</span>
                  <button
                    type="button"
                    onClick={() => updateQty(item.menuId, item.qty + 1)}
                    aria-label="수량 늘리기"
                    className="text-neutral-900"
                  >
                    +
                  </button>
                </div>
              </div>
            </div>
          </div>
        ))}
        {items.length === 0 && (
          <p className="py-20 text-center text-body-1 text-neutral-400">장바구니가 비어있어요.</p>
        )}

        {/* 자릿세는 주문과 함께만 청구된다 — 빈 장바구니에서 하단 탭으로 들어오면 "비어있어요" 옆에
            "자릿세 2명 × 3,000원 / 총 6,000원"이 뜨던 것을 막는다 */}
        {items.length > 0 && seatFee !== null && seatFee > 0 && (
          <>
            <div className="flex items-center justify-between border-t border-neutral-100 py-3 text-body-1 text-neutral-900">
              <span>
                자릿세 {partySize}명 × {seatFeePerPerson.toLocaleString()}원
              </span>
              <span>{seatFee.toLocaleString()}원</span>
            </div>
            <p className="pb-3 text-body-3 text-neutral-500">자릿세는 첫 주문에만 별도 주문으로 함께 청구돼요.</p>
          </>
        )}
        {items.length > 0 && seatFee === null && seatFeePerPerson > 0 && (
          <p className="pb-3 text-body-3 text-neutral-500">
            첫 주문에는 자릿세(1인 {seatFeePerPerson.toLocaleString()}원)가 별도 주문으로 함께 청구돼요.
          </p>
        )}
      </div>

      <div className="border-t border-neutral-200 bg-neutral-50 px-6 pt-5 pb-8">
        {/* 실패·호출 문구는 버튼과 같은 고정 영역에 둔다 — 스크롤되는 목록 끝에 있으면 장바구니가 길 때
            화면 밖이라, 손님은 아무 일도 안 일어난 줄 알고 다시 누른다 */}
        {error && <p className="mb-3 text-body-3 text-red-600">{error}</p>}
        {callMessage && <p className="mb-3 text-center text-body-3 text-neutral-400">{callMessage}</p>}
        <div className="mb-2 flex items-center justify-between">
          <span className="text-body-1 text-neutral-900">총 주문금액</span>
          <span className="text-heading-2 text-neutral-900">
            {(totalAmount + (items.length > 0 ? (seatFee ?? 0) : 0)).toLocaleString()}원
          </span>
        </div>
        {items.length > 0 && shortfall > 0 && (
          <p className="mb-2 text-center text-body-3 text-red-600">
            첫 주문은 {minOrderAmount.toLocaleString()}원 이상부터 가능해요 (자릿세 제외). {shortfall.toLocaleString()}원 더
            담아주세요.
          </p>
        )}
        <p className="mb-3 text-center text-body-3 text-neutral-400">
          결제는 계좌이체로만 진행돼요. 신중하게 주문해주세요.
        </p>
        <button
          type="button"
          disabled={loading || previewLoading || items.length === 0 || shortfall > 0}
          onClick={handleSubmit}
          className={`${CUSTOMER_BUTTON_BASE} bg-primary-300 text-heading-3 text-white disabled:opacity-40`}
        >
          {loading ? '주문 중...' : '주문하기'}
        </button>
      </div>

      {showCallConfirm && (
        <StaffCallConfirmModal onConfirm={handleCallStaff} onCancel={closeCallConfirm} />
      )}
    </div>
  )
}
