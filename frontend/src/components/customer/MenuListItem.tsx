import { PlusIcon } from './icons'
import type { CustomerMenuItem } from '../../types/customer'

type MenuListItemProps = {
  menu: CustomerMenuItem
  orderingDisabled?: boolean
  onAdd: () => void
}

export default function MenuListItem({ menu, orderingDisabled, onAdd }: MenuListItemProps) {
  const disabled = menu.soldOut || orderingDisabled

  return (
    <div className="relative flex h-[112px] w-full gap-[19px] rounded-[12px] border border-neutral-200 bg-white p-4">
      <div className="relative h-20 w-20 shrink-0 overflow-hidden rounded-[12px] bg-[#d9d9d9]">
        {menu.imageUrl && <img src={menu.imageUrl} alt="" className="h-full w-full object-cover" />}
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
          <button
            type="button"
            onClick={onAdd}
            disabled={disabled}
            aria-label={`${menu.name} 담기`}
            className="text-neutral-900 disabled:text-neutral-300"
          >
            <PlusIcon className="h-6 w-6" />
          </button>
        </div>
      </div>
    </div>
  )
}