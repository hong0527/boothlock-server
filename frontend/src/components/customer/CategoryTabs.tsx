export type MenuCategory = 'ALL' | 'MAIN' | 'SIDE' | 'DRINK'

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
    <div className="flex gap-2 overflow-x-auto px-5 py-3">
      {TABS.map((tab) => (
        <button
          key={tab.key}
          type="button"
          onClick={() => onChange(tab.key)}
          className={`shrink-0 rounded-full px-4 py-2 text-body-2 ${
            active === tab.key ? 'bg-neutral-900 text-neutral-50' : 'bg-neutral-100 text-neutral-500'
          }`}
        >
          {tab.label}
        </button>
      ))}
    </div>
  )
}