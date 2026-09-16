import { CloseIcon } from './icons'

type StaffCallModalProps = {
  open: boolean
  onClose: () => void
  onConfirm: () => void
}

export default function StaffCallModal({ open, onClose, onConfirm }: StaffCallModalProps) {
  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 bg-black/30" aria-label="직원 호출">
      <div className="absolute left-1/2 top-1/2 h-[145px] w-[min(302px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2 rounded-[12px] border-0 border-black bg-white">
        <div className="absolute inset-[20px_15px_16px_24px]">
          <h2 className="absolute left-0 top-0 m-0 text-[16px] leading-[1.2] font-medium text-black">직원 호출</h2>
          <p className="absolute left-0 top-[33px] m-0 text-[13px] leading-[1.2] text-[#555]">직원을 호출하시겠습니까?</p>
          <button
            type="button"
            onClick={onConfirm}
            className="absolute bottom-0 left-[85px] h-[36px] w-[84px] rounded-[6px] border-[0.3px] border-white bg-[#9df6d2] text-[15px] leading-[1.2] font-medium text-black"
          >
            네
          </button>
          <button
            type="button"
            onClick={onClose}
            className="absolute bottom-0 right-0 h-[36px] w-[84px] rounded-[6px] border-[0.3px] border-primary-100 bg-white text-[15px] leading-[1.2] font-medium text-black"
          >
            아니오
          </button>
          <button type="button" onClick={onClose} aria-label="닫기" className="absolute -right-[3px] -top-[9px] size-[24px]">
            <CloseIcon className="size-[24px]" />
          </button>
        </div>
      </div>
    </div>
  )
}
