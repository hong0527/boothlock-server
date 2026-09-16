import { useNavigate } from 'react-router-dom'

const PERSON_ICON = 'https://www.figma.com/api/mcp/asset/a9e16f9e-2bc9-4d3d-bf71-c473be941e38.svg'
const RECEIPT_ICON = 'https://www.figma.com/api/mcp/asset/c689f579-ecb1-439a-8ff6-372842fe701f.svg'
const CART_ICON = 'https://www.figma.com/api/mcp/asset/0e902c1a-2b32-4d9f-bd71-1be33331e9f8.svg'

type CustomerBottomNavProps = {
  onStaffCall?: () => void
  onOrderHistory?: () => void
  onCart?: () => void
  onCallStaff?: () => void
  cartCount?: number
}

export default function CustomerBottomNav({
  onStaffCall,
  onOrderHistory,
  onCart,
  onCallStaff,
  cartCount = 0,
}: CustomerBottomNavProps) {
  const navigate = useNavigate()

  return (
    <nav className="fixed inset-x-0 bottom-0 z-40 h-[97px] w-full bg-primary-50" aria-label="손님 메뉴">
      <button
        type="button"
        onClick={onStaffCall ?? onCallStaff}
        className="absolute left-1/6 top-[17px] flex h-[51px] w-1/6 -translate-x-1/2 flex-col items-center gap-1 text-[14px] leading-[1.2] font-medium tracking-[-0.56px] text-black"
      >
        <img src={PERSON_ICON} alt="" className="size-[28px]" />
        직원 호출
      </button>
      <button
        type="button"
        onClick={onOrderHistory ?? (() => navigate('/order-history'))}
        className="absolute left-1/2 top-[19px] flex h-[51px] w-1/6 -translate-x-1/2 flex-col items-center gap-1 text-[14px] leading-[1.2] font-medium tracking-[-0.56px] text-black"
      >
        <img src={RECEIPT_ICON} alt="" className="size-[30px]" />
        주문내역
      </button>
      <button
        type="button"
        onClick={onCart ?? (() => navigate('/cart'))}
        className="absolute left-5/6 top-[21px] flex h-[48px] w-1/6 -translate-x-1/2 flex-col items-center gap-1 text-[14px] leading-[1.2] font-medium tracking-[-0.56px] text-black"
      >
        <img src={CART_ICON} alt="" className="size-[28px]" />
        장바구니
        {cartCount > 0 && (
          <span className="absolute -right-[3px] -top-[5px] flex min-h-[18px] min-w-[18px] items-center justify-center rounded-full bg-black px-1 text-[11px] leading-none font-semibold text-white">
            {cartCount}
          </span>
        )}
      </button>
      <span aria-hidden="true" className="absolute left-1/3 top-[19px] h-[49px] w-px bg-neutral-200" />
      <span aria-hidden="true" className="absolute left-2/3 top-[19px] h-[49px] w-px bg-neutral-200" />
    </nav>
  )
}
