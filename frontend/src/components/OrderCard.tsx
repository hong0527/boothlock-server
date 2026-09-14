import type { OrderSummary } from '../types/dashboard'
import { formatClockTime, formatElapsed } from '../lib/time'

type OrderCardProps = {
  order: OrderSummary
  now: number
  onComplete: (orderId: number) => void
  onCancel: (orderId: number) => void
  onRevert: (orderId: number) => void
}

export default function OrderCard({ order, now, onComplete, onCancel, onRevert }: OrderCardProps) {
  return (
    <div className="flex min-h-[321px] w-full flex-col rounded-xl border border-neutral-200 bg-neutral-50 p-4">
      <div className="flex items-start justify-between">
        <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
          {order.tableLabel ?? '테이블 미지정'}
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
          <button
            type="button"
            onClick={() => onCancel(order.orderId)}
            className="h-[54px] flex-1 rounded-xl bg-neutral-600 text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-50"
          >
            취소
          </button>
          <button
            type="button"
            onClick={() => onComplete(order.orderId)}
            className="h-[54px] flex-1 rounded-xl bg-neutral-600 text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-50"
          >
            완료
          </button>
        </div>
      )}

      {order.status !== 'RECEIVED' && (
        <div className="mt-4">
          <button
            type="button"
            onClick={() => onRevert(order.orderId)}
            className="h-[54px] w-full rounded-xl bg-neutral-600 text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-50"
          >
            되돌리기
          </button>
        </div>
      )}
    </div>
  )
}
