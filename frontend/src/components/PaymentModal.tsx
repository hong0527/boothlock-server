import { useEffect, useState } from 'react'
import closeIcon from '../assets/icons/x.svg'
import PrimaryButton from './PrimaryButton'
import { readApiError } from '../lib/apiError'
import { apiFetch } from '../lib/apiFetch'
import { cancelItem, cancelOrder, checkoutTable, confirmTablePayment, updateItemQty } from '../lib/orderActions'
import { isUnpaid, unpaidTotal } from '../lib/sessionOrders'
import { displayTableLabel } from '../lib/tableLabel'
import { formatClockTime } from '../lib/time'
import { PAYMENT_STATUS_LABEL, type OrderStatus, type OrderSummary, type PaymentStatus } from '../types/dashboard'
import type { TableCheckoutResult, TableStatusInfo } from '../types/table'

type PaymentModalProps = {
  table: TableStatusInfo
  onClose: () => void
  /** 퇴실(O6)까지 끝났을 때 — 호출부가 모달을 닫고 테이블 목록을 다시 읽는다 */
  onCheckedOut: () => void
}

const POLL_INTERVAL_MS = 5000

type FlatItem = {
  orderId: number
  itemId: number
  menuName: string
  unitPrice: number
  qty: number
  status: OrderStatus
  paymentStatus: PaymentStatus
}

// 항목 수정(수량·개별 취소)이 409로 거부되는 이유를 코드별로 — 백엔드 DashboardOrderActionService 검사 순서와 같다
const ITEM_ACTION_409: Record<string, string> = {
  SOLD_OUT: '품절된 메뉴라 수량을 늘릴 수 없어요',
  ORDER_CLOSED: '주문 접수가 마감돼 수량을 늘릴 수 없어요',
  INVALID_STATE: '접수·미결제 상태의 주문만 수정할 수 있어요',
}

export default function PaymentModal({ table, onClose, onCheckedOut }: PaymentModalProps) {
  const [orders, setOrders] = useState<OrderSummary[]>([])
  // 동작 실패 문구(409 등). 목록 새로고침이 성공해도 지우지 않는다 — 새로고침이 문구를 덮어 운영자가 거절 사유를 못 보던 결함(E2E F1)
  const [error, setError] = useState<string | null>(null)
  // 목록 조회 자체의 실패 문구. 동작 문구와 분리해 서로 덮어쓰지 않게 한다
  const [loadError, setLoadError] = useState<string | null>(null)
  const [checkingOut, setCheckingOut] = useState(false)
  // 요청 진행 중엔 항목 버튼을 다 막는다 — 안 막으면 연타 시 새로고침 전 값 기준으로 요청이 겹쳐서 변경분이 씹힌다
  const [busy, setBusy] = useState(false)

  const refetch = async () => {
    try {
      // businessDate 생략 = 현재 영업일. activeSessionOnly=true — 그 테이블의 종료 안 된 세션(지금 앉은 손님) 주문만 서버가 골라 준다.
      // 세션이 없는 테이블은 빈 목록. 이전 손님의 PAID·DONE이 결제 대상·전체 취소 대상에 섞이지 않는다 (audit2 ②-2·②-3)
      const res = await apiFetch(`/api/v1/admin/orders?tableId=${table.id}&activeSessionOnly=true`)
      if (!res.ok) throw new Error(`주문 내역을 불러오지 못했어요 (${res.status})`)
      const data: { orders: OrderSummary[] } = await res.json()
      setOrders(data.orders)
      setLoadError(null)
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '주문 내역을 불러오지 못했어요.')
    }
  }

  useEffect(() => {
    refetch()
    const id = setInterval(refetch, POLL_INTERVAL_MS)
    return () => clearInterval(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [table.id])

  // 취소된 주문만 뺀다 — 완료(DONE) 주문도 보인다. 미결제 합계(O24 대상)에 DONE·UNPAID가 들어가므로 목록에도 있어야 합계와 항목이 맞는다.
  // 미결제 정의는 백엔드 UnpaidOrderRule(RECEIVED·DONE && UNPAID)과 같다 — 완료 처리만 되고 입금 안 된 주문도 미수금
  const visibleOrders = orders.filter((o) => o.status !== 'CANCELED')
  // 전체 취소는 접수(RECEIVED) 주문만 — 완료된 주문은 운영자가 주문 현황에서 개별 취소(O13)한다
  const receivedOrders = visibleOrders.filter((o) => o.status === 'RECEIVED')
  const unpaidOrders = visibleOrders.filter(isUnpaid)
  const unpaidAmount = unpaidTotal(visibleOrders)

  // Figma는 "주문" 단위 그룹핑이 없다 — 주문들의 항목을 한 줄씩 평탄화해서 보여준다
  const items: FlatItem[] = visibleOrders.flatMap((o) =>
    o.items.map((item) => ({ orderId: o.orderId, itemId: item.itemId, menuName: item.menuName,
      unitPrice: item.unitPrice, qty: item.qty, status: o.status, paymentStatus: o.paymentStatus })),
  )

  const runAction = async (action: () => Promise<Response>, failMessage: string) => {
    if (busy) return
    setBusy(true)
    const res = await action()
    setBusy(false)
    if (!res.ok) {
      const { code } = await readApiError(res)
      setError(res.status === 409 && code && ITEM_ACTION_409[code] ? ITEM_ACTION_409[code] : `${failMessage} (${res.status})`)
      refetch()
      return
    }
    setError(null)
    refetch()
  }

  const handleCancelAll = async () => {
    if (busy || receivedOrders.length === 0) return
    const paidCount = receivedOrders.filter((o) => o.paymentStatus === 'PAID').length
    const note = paidCount > 0 ? `\n입금확인된 ${paidCount}건은 '환불필요'로 바뀝니다.` : ''
    if (!window.confirm(`이 테이블의 접수 주문 ${receivedOrders.length}건을 모두 취소할까요?${note}`)) return
    setBusy(true)
    for (const order of receivedOrders) {
      const res = await cancelOrder(order.orderId, '테이블 전체 취소')
      if (!res.ok) {
        setError(`전체 취소 중 일부가 실패했어요 (${res.status})`)
        setBusy(false)
        refetch()
        return
      }
    }
    setBusy(false)
    setError(null)
    refetch()
  }

  // O6 퇴실 — 성공하면 모달을 닫는다. 응답의 warning(미결제 남음)은 닫기 전에 한 번 보여준다
  const checkout = async (): Promise<boolean> => {
    const res = await checkoutTable(table.id)
    if (res.status === 410) {
      // 개정 전 백엔드: 이미 퇴실 처리된 테이블 — 할 일이 없으니 닫는다 (개정 후에는 멱등 200)
      onCheckedOut()
      return true
    }
    if (!res.ok) {
      setError(`퇴실 처리에 실패했어요 (${res.status})`)
      return false
    }
    const result: TableCheckoutResult | null = await res.json().catch(() => null)
    if (result?.warning || result?.unpaidWarning) {
      window.alert(result.warning ?? '미결제 주문이 남아 있어요. 주문 현황에서 개별 입금확인이 필요해요.')
    }
    onCheckedOut()
    return true
  }

  // "결제 완료" = 미결제 합계를 확인받고 O24 일괄 입금확인 → 성공하면 O6 퇴실. 미결제 0건이면 O24 없이 바로 퇴실
  const handleConfirmPayment = async () => {
    if (checkingOut || busy) return
    if (unpaidOrders.length === 0) {
      // 화면 목록엔 없는데 O3가 미결제를 세고 있으면(이전 영업일·목록 미반영) 그대로 남는다는 것을 알린다
      const serverNote =
        table.unpaidOrderCount > 0
          ? `\n서버 기준 미결제 ${table.unpaidOrderCount}건이 있지만 현재 목록에 없어요(다른 영업일 주문 등). 퇴실해도 그대로 남습니다.`
          : ''
      if (!window.confirm(`입금 확인할 미결제 주문이 없어요. 퇴실 처리할까요?${serverNote}`)) return
      setCheckingOut(true)
      await checkout()
      setCheckingOut(false)
      return
    }

    const ok = window.confirm(
      `미결제 ${unpaidOrders.length}건 · 합계 ${unpaidAmount.toLocaleString()}원\n계좌이체 입금을 확인하고 퇴실 처리할까요?`,
    )
    if (!ok) return

    setCheckingOut(true)
    const res = await confirmTablePayment(table.id, unpaidAmount, 'BANK_TRANSFER')
    if (!res.ok) {
      setCheckingOut(false)
      if (res.status === 409) {
        // 합계 불일치(그 사이 주문·취소)·대상 0건 — 최신 목록으로 다시 확인받는다
        setError('주문이 바뀌었어요. 새로고침된 내역을 확인한 뒤 다시 눌러주세요.')
      } else {
        const { message } = await readApiError(res)
        setError(message ? `입금 확인에 실패했어요: ${message}` : `입금 확인에 실패했어요 (${res.status})`)
      }
      refetch()
      return
    }
    await checkout()
    setCheckingOut(false)
  }

  // "테이블 비우기" = 입금확인 없이 O6만. 미결제가 남는다는 것을 확인받는다
  const handleVacate = async () => {
    if (checkingOut || busy) return
    const unpaidCount = Math.max(unpaidOrders.length, table.unpaidOrderCount)
    const message =
      unpaidCount > 0
        ? `미결제 ${unpaidCount}건이 그대로 남습니다. 입금 확인 없이 테이블을 비울까요?`
        : '테이블을 비울까요? 손님 화면은 바로 접속이 끊어져요.'
    if (!window.confirm(message)) return
    setCheckingOut(true)
    await checkout()
    setCheckingOut(false)
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-6">
      <div className="flex max-h-[80vh] w-full max-w-[600px] flex-col rounded-2xl bg-neutral-50">
        <div className="flex items-center justify-between border-b border-neutral-200 px-6 py-5">
          <div className="flex items-baseline gap-3">
            <span className="text-[28px] leading-[1.2] font-bold tracking-[-0.04em] text-neutral-900">
              {displayTableLabel(table.label)}
            </span>
            {table.session && (
              <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-400">
                {formatClockTime(table.session.startedAt)}
              </span>
            )}
          </div>
          <button type="button" onClick={onClose} aria-label="닫기">
            <img src={closeIcon} alt="" className="h-9 w-9" />
          </button>
        </div>

        <div className="flex items-center justify-between border-b border-neutral-200 px-6 py-4">
          <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">주문 내역</span>
          <button
            type="button"
            onClick={handleCancelAll}
            disabled={receivedOrders.length === 0 || busy}
            className="rounded-xl border border-neutral-900 px-4 py-3 text-base leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900 disabled:opacity-30"
          >
            전체 취소
          </button>
        </div>

        {(error ?? loadError) && <p className="px-6 pt-3 text-sm text-red-600">{error ?? loadError}</p>}

        <div className="flex-1 overflow-y-auto">
          {items.map((item) => {
            // 서버(OrderEntity.canEditItems)는 접수+미결제만 수정을 허용한다 — 완료·입금확인 항목은 버튼을 미리 막아 409 헛클릭을 없앤다
            const editable = item.status === 'RECEIVED' && item.paymentStatus === 'UNPAID'
            return (
              <div key={item.itemId} className="border-b border-neutral-200 px-6 py-5">
                <div className="flex items-baseline justify-between">
                  <span className="flex items-baseline gap-2 text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
                    {item.menuName}
                    <span
                      className={`rounded-md px-1.5 py-0.5 text-xs font-medium ${
                        item.paymentStatus === 'UNPAID' ? 'bg-neutral-200 text-neutral-700' : 'bg-neutral-900 text-neutral-50'
                      }`}
                    >
                      {PAYMENT_STATUS_LABEL[item.paymentStatus]}
                    </span>
                    {item.status === 'DONE' && (
                      <span className="rounded-md bg-neutral-100 px-1.5 py-0.5 text-xs font-medium text-neutral-500">완료</span>
                    )}
                  </span>
                  <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
                    {(item.unitPrice * item.qty).toLocaleString()}원
                  </span>
                </div>

                <div className="mt-2 flex items-center justify-between">
                  <span className="text-base leading-[1.5] tracking-[-0.04em] text-neutral-900">
                    {item.unitPrice.toLocaleString()}원
                  </span>
                  {/* 입금확인·완료된 주문은 서버가 수정을 409로 거부한다 — 버튼을 미리 막아 헛클릭을 없앤다 */}
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => runAction(() => updateItemQty(item.orderId, item.itemId, item.qty - 1), '수량 변경에 실패했어요')}
                      disabled={item.qty <= 1 || busy || !editable}
                      className="h-8 w-8 rounded-xl border border-neutral-900 text-lg font-semibold text-neutral-900 disabled:opacity-30"
                    >
                      -
                    </button>
                    <span className="w-6 text-center text-lg font-semibold text-neutral-900">{item.qty}</span>
                    <button
                      type="button"
                      onClick={() => runAction(() => updateItemQty(item.orderId, item.itemId, item.qty + 1), '수량 변경에 실패했어요')}
                      disabled={busy || !editable}
                      className="h-8 w-8 rounded-xl border border-neutral-900 text-lg font-semibold text-neutral-900 disabled:opacity-30"
                    >
                      +
                    </button>
                    <button
                      type="button"
                      onClick={() => runAction(() => cancelItem(item.orderId, item.itemId), '취소에 실패했어요')}
                      disabled={busy || !editable}
                      className="rounded-xl border border-neutral-900 px-4 py-2 text-base font-semibold text-neutral-900 disabled:opacity-30"
                    >
                      취소
                    </button>
                  </div>
                </div>
              </div>
            )
          })}
          {items.length === 0 && !error && (
            <p className="px-6 py-10 text-center text-neutral-300">
              {table.session ? '주문 내역이 없어요' : '이용 중인 손님이 없어요'}
            </p>
          )}
        </div>

        <div className="flex flex-col gap-3 p-6">
          <div className="flex items-baseline justify-between text-base tracking-[-0.04em] text-neutral-900">
            <span>미결제 합계 {unpaidOrders.length > 0 && <span className="text-neutral-400">({unpaidOrders.length}건)</span>}</span>
            <span className="text-lg font-semibold">{unpaidAmount.toLocaleString()}원</span>
          </div>
          <div className="flex gap-3">
            <button
              type="button"
              onClick={handleVacate}
              disabled={checkingOut || busy}
              className="h-[60px] flex-1 rounded-xl border border-neutral-900 text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900 disabled:opacity-40"
            >
              테이블 비우기
            </button>
            <PrimaryButton
              type="button"
              onClick={handleConfirmPayment}
              disabled={checkingOut || busy}
              className="flex-1 disabled:opacity-40"
            >
              {checkingOut ? '처리 중...' : '결제 완료'}
            </PrimaryButton>
          </div>
        </div>
      </div>
    </div>
  )
}
