import { PAYMENT_STATUS_LABEL, type OrderSummary } from '../types/dashboard'
import { displayTableLabel } from '../lib/tableLabel'
import { formatClockTime, formatElapsed } from '../lib/time'

const ACTION_BUTTON_BASE =
  'h-[54px] flex-1 rounded-xl text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-50 disabled:opacity-40'

/** 완료·되돌리기 등 카드의 주 동작 (Figma 240:404 / 274:874) */
const ACTION_BUTTON_CLASS = `${ACTION_BUTTON_BASE} bg-primary-300`

/** 취소만 회색으로 남는다 — Figma 240:404에서 완료(초록) 옆에 눌리지 말아야 할 쪽으로 구분해 둔 색 */
const CANCEL_BUTTON_CLASS = `${ACTION_BUTTON_BASE} bg-neutral-300`

type OrderCardProps = {
  order: OrderSummary
  now: number
  pending: boolean
  onComplete: (orderId: number) => void
  onCancel: (orderId: number) => void
  onRestore: (orderId: number) => void
  /** ADMIN이 아니면 넘기지 않는다 — 환불 완료는 ADMIN 전용(백엔드 403) */
  onRefundDone?: (orderId: number) => void
}

export default function OrderCard({ order, now, pending, onComplete, onCancel, onRestore, onRefundDone }: OrderCardProps) {
  return (
    <div className="flex min-h-[321px] w-full flex-col rounded-xl border border-neutral-200 bg-neutral-50 p-4">
      <div className="flex items-start justify-between">
        <span className="flex items-baseline gap-2 text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
          {order.tableLabel ? displayTableLabel(order.tableLabel) : '테이블 미지정'}
          {/* 결제 상태 뱃지 — 운영 수칙 "입금 확인 전 조리·전달 금지"(명세 O12)를 카드에서 바로 판단하게 */}
          <span
            className={`rounded-md px-1.5 py-0.5 text-xs font-medium ${
              order.paymentStatus === 'UNPAID' ? 'bg-neutral-200 text-neutral-700' : 'bg-neutral-900 text-neutral-50'
            }`}
          >
            {PAYMENT_STATUS_LABEL[order.paymentStatus]}
          </span>
          {order.manual && <span className="text-xs font-medium text-neutral-400">수기</span>}
        </span>
        <span className="flex flex-col items-end">
          <span className="text-base leading-[1.5] font-medium tracking-[-0.04em] text-blue-600">
            {formatElapsed(order.createdAt, now)}
          </span>
          <span className="text-xs leading-[1.5] text-neutral-400">{formatClockTime(order.createdAt)}</span>
        </span>
      </div>

      <div className="mt-3 border-t border-neutral-200" />

      <ul className="mt-4 flex flex-1 flex-col gap-2">
        {order.items.map((item) => (
          <li
            key={item.menuId}
            className="flex items-center gap-3 text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900"
          >
            <span>{item.menuName}</span>
            <span>{item.qty}</span>
          </li>
        ))}
      </ul>

      {order.status === 'RECEIVED' && (
        <div className="mt-4 flex gap-4">
          <button type="button" onClick={() => onCancel(order.orderId)} disabled={pending} className={CANCEL_BUTTON_CLASS}>
            취소
          </button>
          <button type="button" onClick={() => onComplete(order.orderId)} disabled={pending} className={ACTION_BUTTON_CLASS}>
            완료
          </button>
        </div>
      )}

      {(order.status === 'DONE' || order.status === 'CANCELED') && (
        <div className="mt-4 flex gap-4">
          <button type="button" onClick={() => onRestore(order.orderId)} disabled={pending} className={ACTION_BUTTON_CLASS}>
            되돌리기
          </button>
          {/* 입금된 주문을 취소하면 '환불필요'로 남는다. 돈을 돌려준 뒤 이걸 눌러야 정산에서 빠진다.
              ADMIN에게만 보인다 — STAFF가 누르면 백엔드가 403을 준다. */}
          {order.paymentStatus === 'REFUND_NEEDED' && onRefundDone && (
            <button
              type="button"
              onClick={() => onRefundDone(order.orderId)}
              disabled={pending}
              className={CANCEL_BUTTON_CLASS}
            >
              환불 완료
            </button>
          )}
        </div>
      )}

    </div>
  )
}
