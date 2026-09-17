import { CloseIcon } from './icons'

/** 직원 호출 재확인 팝업 — Figma 289:4115 */
type StaffCallConfirmModalProps = {
  onConfirm: () => void
  onCancel: () => void
}

export default function StaffCallConfirmModal({ onConfirm, onCancel }: StaffCallConfirmModalProps) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-6">
      <div className="w-full max-w-[320px] rounded-2xl border border-neutral-200 bg-white p-5 shadow-lg">
        <div className="flex items-center justify-between">
          <h2 className="text-heading-3 text-neutral-900">직원 호출</h2>
          <button type="button" onClick={onCancel} aria-label="닫기">
            <CloseIcon className="h-5 w-5 text-neutral-900" />
          </button>
        </div>
        <p className="mt-3 text-body-3 text-neutral-500">직원을 호출하시겠습니까?</p>
        <div className="mt-5 flex gap-2">
          <button
            type="button"
            onClick={onConfirm}
            className="h-[42px] flex-1 rounded-xl bg-primary-50 text-body-2 font-semibold text-neutral-900"
          >
            네
          </button>
          <button
            type="button"
            onClick={onCancel}
            className="h-[42px] flex-1 rounded-xl border border-neutral-200 text-body-2 text-neutral-900"
          >
            아니오
          </button>
        </div>
      </div>
    </div>
  )
}
