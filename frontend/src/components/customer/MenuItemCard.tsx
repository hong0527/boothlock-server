import type { CustomerMenuItem } from '../../pages/customer/menuData'

const PLUS_ICON = 'https://www.figma.com/api/mcp/asset/1f084f48-efdb-4a90-b67c-0d7aa466bee5.svg'

type MenuItemCardProps = {
  item: CustomerMenuItem
  onAdd: (item: CustomerMenuItem) => void
}

export default function MenuItemCard({ item, onAdd }: MenuItemCardProps) {
  return (
    <article className="relative flex h-[121px] w-full shrink-0 rounded-[12px] border-[0.3px] border-neutral-300 bg-white">
      <div className="absolute left-[11px] top-[15px] size-[91px] overflow-hidden rounded-[12px] bg-[#d9d9d9]">
        {item.imageUrl && <img src={item.imageUrl} alt="" className="h-full w-full object-cover" />}
      </div>
      <div className="absolute left-[112px] top-[23px] right-[50px] min-w-0">
        <h2 className="m-0 truncate text-[16px] leading-[1.2] font-medium tracking-[-0.64px] text-black">{item.name}</h2>
        <p className="absolute left-0 top-[21px] m-0 truncate text-[12px] leading-[1.2] tracking-[-0.48px] text-neutral-300">
          {item.description}
        </p>
        <p className="absolute left-0 top-[59px] m-0 text-[14px] leading-[1.2] font-medium tracking-[-0.56px] text-black">
          {item.price.toLocaleString()}원
        </p>
      </div>
      <button
        type="button"
        aria-label={`${item.name} 추가`}
        onClick={() => onAdd(item)}
        disabled={item.soldOut}
        className="absolute right-[16px] top-[67px] flex h-[22px] w-[34px] items-center justify-center rounded-[20px] border-0 bg-white transition-colors duration-150 hover:bg-[#d9d9d9] disabled:cursor-not-allowed disabled:opacity-50"
      >
        <img src={PLUS_ICON} alt="" className="size-[24px]" />
      </button>
    </article>
  )
}
