import { useEffect, useState } from 'react'
import closeIcon from '../assets/icons/x.svg'
import PrimaryButton from './PrimaryButton'
import { apiFetch } from '../lib/apiFetch'
import { cancelItem, cancelOrder, checkoutTable, updateItemQty } from '../lib/orderActions'
import { displayTableLabel } from '../lib/tableLabel'
import { formatClockTime, todayKst } from '../lib/time'
import type { OrderSummary } from '../types/dashboard'
import type { TableStatusInfo } from '../types/table'

type PaymentModalProps = {
  table: TableStatusInfo
  onClose: () => void
}

const POLL_INTERVAL_MS = 5000

type FlatItem = {
  orderId: number
  itemId: number
  menuName: string
  unitPrice: number
  qty: number
}

export default function PaymentModal({ table, onClose }: PaymentModalProps) {
  const [orders, setOrders] = useState<OrderSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  const [checkingOut, setCheckingOut] = useState(false)
  // 요청 진행 중엔 항목 버튼을 다 막는다 — 안 막으면 연타 시 새로고침 전 값 기준으로 요청이 겹쳐서 변경분이 씹힌다
  const [busy, setBusy] = useState(false)

  const refetch = async () => {
    try {
      const res = await apiFetch(`/api/v1/admin/orders?tableId=${table.id}&businessDate=${todayKst()}`)
      if (!res.ok) throw new Error(`주문 내역을 불러오지 못했어요 (${res.status})`)
      const data: { orders: OrderSummary[] } = await res.json()
      setOrders(data.orders)
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : '주문 내역을 불러오지 못했어요.')
    }
  }

  useEffect(() => {
    refetch()
    const id = setInterval(refetch, POLL_INTERVAL_MS)
    return () => clearInterval(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [table.id])

  // Figma는 "주문" 단위 그룹핑이 없다 — 취소되지 않은 주문들의 항목을 한 줄씩 평탄화해서 보여준다
  const activeOrders = orders.filter((o) => o.status !== 'CANCELED')
  const items: FlatItem[] = activeOrders.flatMap((o) =>
    o.items.map((item) => ({ orderId: o.orderId, itemId: item.itemId, menuName: item.menuName,
      unitPrice: item.unitPrice, qty: item.qty })),
  )

  const runAction = async (action: () => Promise<Response>, failMessage: string) => {
    if (busy) return
    setBusy(true)
    const res = await action()
    setBusy(false)
    if (!res.ok) {
      setError(`${failMessage} (${res.status})`)
      return
    }
    setError(null)
    refetch()
  }

  const handleCancelAll = async () => {
    if (busy) return
    setBusy(true)
    for (const order of activeOrders) {
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

  const handleCheckout = async () => {
    if (checkingOut) return
    setCheckingOut(true)
    const res = await checkoutTable(table.id)
    setCheckingOut(false)
    if (res.status === 410) {
      // 이미 퇴실 처리된 테이블 — 할 일이 없으니 그냥 닫는다
      onClose()
      return
    }
    if (!res.ok) {
      setError(`결제 완료 처리에 실패했어요 (${res.status})`)
      return
    }
    onClose()
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
            disabled={activeOrders.length === 0 || busy}
            className="rounded-xl border border-neutral-900 px-4 py-3 text-base leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900 disabled:opacity-30"
          >
            전체 취소
          </button>
        </div>

        {error && <p className="px-6 pt-3 text-sm text-red-600">{error}</p>}

        <div className="flex-1 overflow-y-auto">
          {items.map((item) => (
            <div key={item.itemId} className="border-b border-neutral-200 px-6 py-5">
              <div className="flex items-baseline justify-between">
                <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
                  {item.menuName}
                </span>
                <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
                  {(item.unitPrice * item.qty).toLocaleString()}원
                </span>
              </div>

              <div className="mt-2 flex items-center justify-between">
                <span className="text-base leading-[1.5] tracking-[-0.04em] text-neutral-900">
                  {item.unitPrice.toLocaleString()}원
                </span>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => runAction(() => updateItemQty(item.orderId, item.itemId, item.qty - 1), '수량 변경에 실패했어요')}
                    disabled={item.qty <= 1 || busy}
                    className="h-8 w-8 rounded-xl border border-neutral-900 text-lg font-semibold text-neutral-900 disabled:opacity-30"
                  >
                    -
                  </button>
                  <span className="w-6 text-center text-lg font-semibold text-neutral-900">{item.qty}</span>
                  <button
                    type="button"
                    onClick={() => runAction(() => updateItemQty(item.orderId, item.itemId, item.qty + 1), '수량 변경에 실패했어요')}
                    disabled={busy}
                    className="h-8 w-8 rounded-xl border border-neutral-900 text-lg font-semibold text-neutral-900 disabled:opacity-30"
                  >
                    +
                  </button>
                  <button
                    type="button"
                    onClick={() => runAction(() => cancelItem(item.orderId, item.itemId), '취소에 실패했어요')}
                    disabled={busy}
                    className="rounded-xl border border-neutral-900 px-4 py-2 text-base font-semibold text-neutral-900 disabled:opacity-30"
                  >
                    취소
                  </button>
                </div>
              </div>
            </div>
          ))}
          {items.length === 0 && !error && (
            <p className="px-6 py-10 text-center text-neutral-300">주문 내역이 없어요</p>
          )}
        </div>

        <div className="p-6">
          <PrimaryButton type="button" onClick={handleCheckout} disabled={checkingOut} className="disabled:opacity-40">
            결제 완료
          </PrimaryButton>
        </div>
      </div>
    </div>
  )
}
