import type { AddedToCartEntry } from '../../lib/useAddedToCartToast'

/**
 * "담았어요" 확인 토스트 — 메뉴판(C2)에서 항목을 담을 때마다 잠깐 떠서 알려준다.
 * 하단 네비(CustomerBottomNav, h-[97px])보다 위에 뜨게 bottom-[113px]로 띄운다.
 * pointer-events-none이라 화면 조작을 절대 막지 않는다 — 알림일 뿐 버튼이 아니다.
 */
export default function AddedToCartToast({ entry }: { entry: AddedToCartEntry | null }) {
  if (!entry) return null
  return (
    <div
      aria-live="polite"
      role="status"
      className={`pointer-events-none fixed inset-x-0 bottom-[113px] z-50 flex justify-center px-6 transition-opacity duration-200 ${
        entry.fading ? 'opacity-0' : 'opacity-100'
      }`}
    >
      <span className="rounded-full bg-neutral-900/90 px-4 py-2 text-body-3 font-medium text-white shadow-lg">
        {entry.message}
      </span>
    </div>
  )
}
