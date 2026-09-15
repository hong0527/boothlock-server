import type { CustomerMenuItem } from '../../types/customer'

type MenuListItemProps = {
  menu: CustomerMenuItem
  /** 부스가 주문 접수를 닫아둔 경우(isOpen: false) — 메뉴는 보이되 담기는 막는다 */
  orderingDisabled?: boolean
  onAdd: () => void
}

export default function MenuListItem({ menu, orderingDisabled, onAdd }: MenuListItemProps) {
  const disabled = menu.soldOut || orderingDisabled

  return (
    <div className="flex gap-4 border-b border-neutral-100 px-5 py-4">
      <div className="relative h-20 w-20 shrink-0 overflow-hidden rounded-xl bg-neutral-200">
        {menu.imageUrl && <img src={menu.imageUrl} alt="" className="h-full w-full object-cover" />}
        {menu.soldOut && (
          <div className="absolute inset-0 flex items-center justify-center bg-black/50">
            <span className="text-caption text-neutral-50">SOLD OUT</span>
          </div>
        )}
      </div>

      <div className="flex flex-1 flex-col justify-between">
        <div>
          <p className="text-heading-3 text-neutral-900">{menu.name}</p>
          {menu.description && <p className="mt-1 text-body-3 text-neutral-400">{menu.description}</p>}
        </div>
        <div className="flex items-end justify-between">
          <span className="text-heading-3 text-neutral-900">{menu.price.toLocaleString()}원</span>
          <button
            type="button"
            onClick={onAdd}
            disabled={disabled}
            aria-label={`${menu.name} 담기`}
            className="flex h-9 w-9 items-center justify-center rounded-full bg-primary-100 text-heading-3 text-neutral-900 disabled:bg-neutral-200 disabled:text-neutral-400"
          >
            +
          </button>
        </div>
      </div>
    </div>
  )
}