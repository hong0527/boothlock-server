import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import BackButton from '../../components/customer/BackButton'
import { CopyIcon, InfoIcon } from '../../components/customer/icons'
import StaffCallConfirmModal from '../../components/customer/StaffCallConfirmModal'
import { CUSTOMER_BUTTON_BASE } from '../../components/controlStyles'
import { customerApiFetch } from '../../lib/customerApiFetch'
import { getSessionInfo } from '../../lib/customerSession'
import { depositorGuide } from '../../lib/depositorName'
import { onResume } from '../../lib/onResume'
import { createPollGuard } from '../../lib/pollGuard'
import { requestStaffCall } from '../../lib/staffCall'
import { displayTableLabel } from '../../lib/tableLabel'
import type { OrderSummary } from '../../types/customer'

// 가벼운 폴링 — 운영자가 승인·거절·입금 확인하면 이체할 합계가 바뀐다. 주문내역(7초)보다 느슨하게 둔다
const POLL_INTERVAL_MS = 10000

export default function PaymentInfoPage() {
  const navigate = useNavigate()
  const [orders, setOrders] = useState<OrderSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  // 무엇을 복사했는지 — 계좌번호와 입금자명(주문번호) 복사 안내를 구분한다
  const [copied, setCopied] = useState<'account' | 'depositor' | null>(null)
  const [callMessage, setCallMessage] = useState<string | null>(null)
  const [showCallConfirm, setShowCallConfirm] = useState(false)

  // 예전에는 처음 한 번만 읽어, 화면을 켜 둔 채 운영자가 승인대기를 거절·승인하거나 입금 확인해도 옛 합계를 이체하라고 보여줬다.
  // 다른 손님 화면(주문내역)과 같은 pollGuard(겹침·응답 역전 방지)+onResume(폰 잠금 해제·재연결 즉시 갱신)을 쓴다
  const pollGuard = useRef(createPollGuard())
  const fetchOrders = async (skipIfBusy = false) => {
    const runId = pollGuard.current.begin(skipIfBusy)
    if (runId === null) return
    try {
      const res = await customerApiFetch('/api/v1/orders')
      if (!res.ok) throw new Error(`주문 정보를 불러오지 못했어요 (${res.status})`)
      const data: { orders: OrderSummary[] } = await res.json()
      if (!pollGuard.current.isLatest(runId)) return
      setOrders(data.orders)
      setError(null)
    } catch (err) {
      if (!pollGuard.current.isLatest(runId)) return
      setError(err instanceof Error ? err.message : '주문 정보를 불러오지 못했어요.')
    } finally {
      pollGuard.current.end()
    }
  }

  useEffect(() => {
    fetchOrders()
    const id = setInterval(() => fetchOrders(true), POLL_INTERVAL_MS)
    const offResume = onResume(() => fetchOrders(true))
    return () => {
      clearInterval(id)
      offResume()
    }
    // 마운트 때 한 번만 구독한다 — fetchOrders는 ref(pollGuard)와 setState만 쓰므로 옛 클로저여도 결과가 같다(PaymentModal과 같은 방식)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 이미 한 번 받은 뒤의 폴링 실패는 화면을 에러로 갈아엎지 않는다 — 계좌·합계는 직전 값으로 두고 다음 주기에 다시 읽는다
  if (error && !orders) {
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

  // 미결제 = 서빙 여부와 무관하게 입금이 안 된 주문 — 취소된 주문(CANCELED+UNPAID)은 받을 돈이 없어 제외.
  // PENDING_APPROVAL도 포함한다 — 손님은 승인 전에 입금하고 결제확인 호출을 보내는 게 정상 플로우라
  // (O11이 PENDING_APPROVAL도 결제확인 허용) 여기서 빼면 방금 주문한 손님이 계좌 안내 자체를 못 본다.
  // (staff 쪽 lib/sessionOrders.ts의 isUnpaid는 PaymentModal 전용이라 PENDING_APPROVAL을 일부러 뺀다 — 여기와는 다른 정의)
  const unpaidOrders = orders.filter(
    (order) =>
      (order.status === 'PENDING_APPROVAL' || order.status === 'RECEIVED' || order.status === 'DONE') &&
      order.paymentStatus === 'UNPAID',
  )
  const totalAmount = unpaidOrders.reduce((sum, order) => sum + order.totalAmount, 0)
  const bankAccount = unpaidOrders[0]?.payment.bankAccount ?? orders[0]?.payment.bankAccount ?? ''
  const depositorName = unpaidOrders[0]?.payment.depositorName ?? orders[0]?.payment.depositorName ?? null

  // 계좌 등록 화면(AccountPage)이 "은행명 계좌번호"를 공백 하나로 이어붙여 저장하므로 그 규칙 그대로 되돌린다.
  // 규칙에 안 맞는 값(수기로 다르게 입력된 경우 등)이면 나누지 않고 한 줄로 보여준다.
  const [bankName, ...rest] = bankAccount.split(' ')
  const accountNumber = rest.join(' ')

  // 입금자명 규칙(서버 C4 depositorNameRule) — 35개 테이블 이체를 운영자가 구분하는 유일한 단서라 크게 보여준다
  const guide = depositorGuide(unpaidOrders)
  const tableLabel = getSessionInfo()?.tableLabel ?? null

  const copyText = async (text: string, what: 'account' | 'depositor') => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(what)
    } catch {
      setCopied(null)
    }
  }
  const handleCopy = () => copyText(accountNumber || bankAccount, 'account')

  // 결제확인 전용 직원호출(PAYMENT, v0.6.11) — 일반 호출(HELP)과 쿨다운이 분리돼 있어 여기서 눌러도 막히지 않는다
  const handleCallStaff = async () => {
    setShowCallConfirm(false)
    const message = await requestStaffCall('PAYMENT')
    if (message) setCallMessage(message)
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
            <div className="text-center">
              <p className="text-heading-2 text-neutral-900">
                아래 계좌로
                <br />
                주문 금액을 입금해주세요.
              </p>
              <p className="mt-2 text-body-3 text-neutral-500">입금이 확인되면 조리가 시작됩니다.</p>
            </div>

            <div className="mt-8 rounded-[10px] border border-[#b8dcd3] bg-primary-50 p-5">
              <p className="text-body-2 text-neutral-900">입금 계좌</p>
              <div className="mt-3 flex items-start gap-1 text-body-2 text-neutral-900">
                <span className="mt-1">•</span>
                {accountNumber ? (
                  <div className="flex flex-1 flex-col gap-1">
                    <span>{bankName}</span>
                    <div className="flex items-center gap-2">
                      <span className="underline">{accountNumber}</span>
                      <button type="button" onClick={handleCopy} aria-label="계좌번호 복사" className="text-neutral-900">
                        <CopyIcon className="h-[19px] w-[19px]" />
                      </button>
                    </div>
                    {depositorName && <span>예금주: {depositorName}</span>}
                  </div>
                ) : (
                  <div className="flex flex-1 items-center gap-2">
                    <span className="underline">{bankAccount}</span>
                    <button type="button" onClick={handleCopy} aria-label="계좌번호 복사" className="text-neutral-900">
                      <CopyIcon className="h-[19px] w-[19px]" />
                    </button>
                  </div>
                )}
              </div>
              <div className="mt-3 flex items-center justify-between border-t border-neutral-100 pt-3 text-body-2 text-neutral-900">
                <span>미결제 주문 {unpaidOrders.length}건 합계</span>
                <span>{totalAmount.toLocaleString()}원</span>
              </div>
            </div>

            {guide && (
              <div className="mt-3 rounded-[10px] border-2 border-primary-300 bg-white p-5">
                <p className="text-body-2 text-neutral-900">
                  입금자명{tableLabel && ` · ${displayTableLabel(tableLabel)}`}
                </p>
                <div className="mt-2 flex items-center gap-2">
                  <span className="text-heading-2 text-neutral-900">이름 + {guide.orderNo}</span>
                  <button
                    type="button"
                    onClick={() => copyText(guide.orderNo, 'depositor')}
                    aria-label="입금자명에 넣을 주문번호 복사"
                    className="text-neutral-900"
                  >
                    <CopyIcon className="h-[19px] w-[19px]" />
                  </button>
                </div>
                <p className="mt-2 text-body-3 text-neutral-700">{guide.rule}</p>
                {guide.multiple && (
                  <p className="mt-1 text-caption text-neutral-500">
                    주문이 여러 건이면 합계를 한 번에 보내고, 입금자명에는 위 주문번호를 넣어주세요.
                  </p>
                )}
              </div>
            )}

            <div className="mt-3 flex items-center gap-2 rounded-[10px] bg-neutral-100 px-4 py-3">
              <InfoIcon className="h-[18px] w-[18px] text-neutral-700" />
              <p className="text-caption text-neutral-700">결제 후에는 취소가 어려워요.</p>
            </div>

            {copied && (
              <p className="mt-2 text-center text-caption text-neutral-400">
                {copied === 'depositor' ? '주문번호가 복사됐어요. 이름 뒤에 붙여 넣어주세요.' : '복사됐어요.'}
              </p>
            )}
          </>
        )}

        {callMessage && <p className="mt-4 text-center text-body-3 text-neutral-400">{callMessage}</p>}
      </div>

      <div className="flex flex-col gap-[6px] px-[18px] pb-8">
        {unpaidOrders.length > 0 && (
          <button
            type="button"
            onClick={() => setShowCallConfirm(true)}
            className={`${CUSTOMER_BUTTON_BASE} border border-primary-300 bg-white text-heading-3 text-primary-300`}
          >
            입금했어요, 직원 호출
          </button>
        )}
        <button
          type="button"
          onClick={() => navigate('/order-history', { replace: true })}
          className={`${CUSTOMER_BUTTON_BASE} bg-primary-300 text-heading-3 text-white`}
        >
          주문내역 확인
        </button>
        <button
          type="button"
          onClick={() => navigate('/order', { replace: true })}
          className={`${CUSTOMER_BUTTON_BASE} bg-primary-300 text-heading-3 text-white`}
        >
          완료
        </button>
      </div>

      {showCallConfirm && (
        <StaffCallConfirmModal onConfirm={handleCallStaff} onCancel={() => setShowCallConfirm(false)} />
      )}
    </div>
  )
}