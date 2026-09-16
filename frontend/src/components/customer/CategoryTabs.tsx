import type { MenuCategoryCode } from '../../types/customer'

/** 'ALL'은 프론트 전용 탭 키 — 백엔드 category 값(MAIN·SIDE·DRINK)에는 없다 */
export type MenuCategory = 'ALL' | MenuCategoryCode

const TABS: { key: MenuCategory; label: string }[] = [
  { key: 'ALL', label: '전체' },
  { key: 'MAIN', label: '메인메뉴' },
  { key: 'SIDE', label: '사이드' },
  { key: 'DRINK', label: '음료' },
]

type CategoryTabsProps = {
  active: MenuCategory
  onChange: (category: MenuCategory) => void
}

export default function CategoryTabs({ active, onChange }: CategoryTabsProps) {
  return (
    <div className="flex gap-[9px] overflow-x-auto px-[17px] pt-3 pb-[13px]">
      {TABS.map((tab) => (
        <button
          key={tab.key}
          type="button"
          onClick={() => onChange(tab.key)}
          className={`h-9 shrink-0 rounded-[12px] px-[17px] text-body-1 ${
            active === tab.key ? 'bg-black text-white' : 'bg-neutral-100 text-neutral-600'
          }`}
        >
          {tab.label}
        </button>
      ))}
    </div>
  )
}