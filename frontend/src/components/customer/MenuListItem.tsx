import { useRef, useState } from 'react'
import { PlusIcon } from './icons'
import { assetUrl } from '../../lib/apiBase'
import type { CustomerMenuItem } from '../../types/customer'

type MenuListItemProps = {
  menu: CustomerMenuItem
  orderingDisabled?: boolean
  onAdd: () => void
}

// 실제 손가락 탭은 80~150ms로 아주 짧아서, pointerdown→pointerup 사이에만 눌림 효과를 주면
// 빠르게 탭했을 때 거의 안 보인다(실측 피드백). 최소 이 시간만큼은 눌린 채로 유지해 항상 눈에 보이게 한다
const MIN_PRESS_MS = 120

export default function MenuListItem({ menu, orderingDisabled, onAdd }: MenuListItemProps) {
  const disabled = menu.soldOut || orderingDisabled
  // iOS Safari는 CSS :active가 탭(터치)에는 기본으로 안 걸리는 고질적 버그가 있다(마우스에만 반응, 실측).
  // active: 유틸리티 대신 포인터 이벤트로 눌림 상태를 직접 관리해 모든 기기에서 동일하게 동작하게 한다
  const [pressed, setPressed] = useState(false)
  const pressedAtRef = useRef(0)
  const releaseTimerRef = useRef<number | undefined>(undefined)

  const handlePress = () => {
    window.clearTimeout(releaseTimerRef.current)
    pressedAtRef.current = Date.now()
    setPressed(true)
  }
  const handleRelease = () => {
    const elapsed = Date.now() - pressedAtRef.current
    const remaining = MIN_PRESS_MS - elapsed
    if (remaining <= 0) {
      setPressed(false)
    } else {
      releaseTimerRef.current = window.setTimeout(() => setPressed(false), remaining)
    }
  }

  return (
    // 카드 전체를 눌러도 담기게 — + 아이콘은 그대로 두되 장식으로만(중첩 버튼 방지를 위해 span)
    <button
      type="button"
      onClick={onAdd}
      disabled={disabled}
      onPointerDown={handlePress}
      onPointerUp={handleRelease}
      onPointerLeave={handleRelease}
      onPointerCancel={handleRelease}
      aria-label={`${menu.name} 담기`}
      className={`relative flex h-[112px] w-full gap-[19px] rounded-[12px] border border-neutral-200 bg-white p-4 text-left transition-transform duration-150 disabled:opacity-60 ${
        pressed && !disabled ? 'scale-[0.97] bg-neutral-50' : ''
      }`}
    >
      <div className="relative h-20 w-20 shrink-0 overflow-hidden rounded-[12px] bg-[#d9d9d9]">
        {menu.imageUrl && <img src={assetUrl(menu.imageUrl)} alt="" loading="lazy" decoding="async" className="h-full w-full object-cover" />}
        {menu.soldOut && (
          <div className="absolute inset-0 flex items-center justify-center bg-black/50">
            <span className="text-caption text-neutral-50">SOLD OUT</span>
          </div>
        )}
      </div>

      <div className="flex flex-1 flex-col justify-between">
        <div>
          <p className="text-body-1 text-neutral-900">{menu.name}</p>
          {menu.description && <p className="mt-1 text-body-3 text-neutral-300">{menu.description}</p>}
        </div>
        <div className="flex items-center justify-between">
          <span className="text-body-1 text-neutral-900">{menu.price.toLocaleString()}원</span>
          <span className="text-neutral-900">
            <PlusIcon className="h-6 w-6" />
          </span>
        </div>
      </div>
    </button>
  )
}