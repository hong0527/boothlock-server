export type MenuCategory = 'ALL' | 'MAIN' | 'SIDE' | 'DRINK'

type CategoryTabsProps = {
  value?: MenuCategory
  active?: MenuCategory
  onChange: (category: MenuCategory) => void
}

const CATEGORIES: { value: MenuCategory; label: string; width: string }[] = [
  { value: 'ALL', label: '전체', width: 'w-[68px]' },
  { value: 'MAIN', label: '메인메뉴', width: 'w-[83px]' },
  { value: 'SIDE', label: '사이드', width: 'w-[68px]' },
  { value: 'DRINK', label: '음료', width: 'w-[68px]' },
]

export default function CategoryTabs({ value, active, onChange }: CategoryTabsProps) {
  const selectedCategory = value ?? active ?? 'ALL'

  return (
    <div className="flex h-[61px] w-full shrink-0 items-start gap-[9px] border-b border-neutral-300 bg-neutral-50 px-[17px] pt-3">
      {CATEGORIES.map((category) => (
        <button
          key={category.value}
          type="button"
          onClick={() => onChange(category.value)}
          className={`${category.width} max-[374px]:w-auto max-[374px]:flex-1 min-[376px]:w-auto min-[376px]:flex-1 h-[36px] min-w-0 rounded-[12px] text-[15px] leading-[1.2] font-semibold tracking-[-0.72px] ${
            selectedCategory === category.value ? 'bg-black text-white' : 'bg-neutral-100 text-neutral-600'
          }`}
        >
          {category.label}
        </button>
      ))}
    </div>
  )
}
