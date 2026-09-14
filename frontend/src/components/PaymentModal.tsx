import closeIcon from '../assets/icons/x.svg'
import PrimaryButton from './PrimaryButton'
import { formatClockTime } from '../lib/time'
import { useTableOrders } from '../context/TableOrderContext'
import type { TableOrder } from '../types/table'

type PaymentModalProps = {
  table: TableOrder
  onClose: () => void
}

export default function PaymentModal({ table, onClose }: PaymentModalProps) {
  const { updateItemQty, cancelItem, cancelAllItems, completePayment } = useTableOrders()

  const handleCompletePayment = () => {
    // TODO: API 연결 시 O11 입금확인/완료 처리로 교체. 지금은 로컬에서 테이블만 비움
    completePayment(table.id)
    onClose()
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-6">
      <div className="flex max-h-[80vh] w-full max-w-[600px] flex-col rounded-2xl bg-neutral-50">
        <div className="flex items-center justify-between border-b border-neutral-200 px-6 py-5">
          <div className="flex items-baseline gap-3">
            <span className="text-2xl leading-[1.2] font-bold tracking-[-0.04em] text-neutral-900">
              {table.label}
            </span>
            <span className="text-base leading-[1.5] text-neutral-400">{formatClockTime(table.startedAt)}</span>
          </div>
          <button type="button" onClick={onClose} aria-label="닫기">
            <img src={closeIcon} alt="" className="h-6 w-6" />
          </button>
        </div>

        <div className="flex items-center justify-between border-b border-neutral-200 px-6 py-4">
          <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">주문 내역</span>
          <button
            type="button"
            onClick={() => cancelAllItems(table.id)}
            disabled={table.items.length === 0}
            className="rounded-xl border border-neutral-900 px-4 py-3 text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900 disabled:opacity-30"
          >
            전체 취소
          </button>
        </div>

        <div className="flex-1 overflow-y-auto">
          {table.items.map((item) => (
            <div key={item.id} className="border-b border-neutral-200 px-6 py-5">
              <div className="flex items-center justify-between text-[22px] leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
                <span>{item.name}</span>
                <span>{(item.unitPrice * item.qty).toLocaleString()}원</span>
              </div>
              <div className="mt-2 flex items-center justify-between">
                <span className="text-base leading-[1.5] tracking-[-0.04em] text-neutral-900">
                  {item.unitPrice.toLocaleString()}원
                </span>
                <div className="flex items-center gap-3">
                  <button
                    type="button"
                    onClick={() => updateItemQty(table.id, item.id, -1)}
                    className="h-8 w-8 rounded-xl border border-neutral-900 text-lg font-semibold text-neutral-900"
                  >
                    -
                  </button>
                  <span className="w-4 text-center text-lg font-semibold text-neutral-900">{item.qty}</span>
                  <button
                    type="button"
                    onClick={() => updateItemQty(table.id, item.id, 1)}
                    className="h-8 w-8 rounded-xl border border-neutral-900 text-lg font-semibold text-neutral-900"
                  >
                    +
                  </button>
                  <button
                    type="button"
                    onClick={() => cancelItem(table.id, item.id)}
                    className="rounded-xl border border-neutral-900 px-3 py-1 text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900"
                  >
                    취소
                  </button>
                </div>
              </div>
            </div>
          ))}
          {table.items.length === 0 && <p className="px-6 py-10 text-center text-neutral-300">주문 내역이 없어요</p>}
        </div>

        <div className="p-6">
          <PrimaryButton type="button" onClick={handleCompletePayment} disabled={table.items.length === 0}>
            결제 완료
          </PrimaryButton>
        </div>
      </div>
    </div>
  )
}
