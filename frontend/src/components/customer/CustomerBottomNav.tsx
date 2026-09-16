import { useNavigate } from 'react-router-dom'
import { CartIcon, PersonIcon, ReceiptIcon } from './icons'

type CustomerBottomNavProps = {
  cartCount: number
  onCallStaff: () => void
}

export default function CustomerBottomNav({ cartCount, onCallStaff }: CustomerBottomNavProps) {
  const navigate = useNavigate()

  return (
    <div className="fixed inset-x-0 bottom-0 flex h-[97px] items-center justify-around bg-primary-50">
      <button
        type="button"
        onClick={onCallStaff}
        className="flex flex-col items-center gap-1 text-body-2 text-neutral-900"
      >
        <PersonIcon className="h-7 w-7" />
        직원 호출
      </button>

      <div className="h-[49px] w-px bg-neutral-200" />

      <button
        type="button"
        onClick={() => navigate('/order-history')}
        className="flex flex-col items-center gap-1 text-body-2 text-neutral-900"
      >
        <ReceiptIcon className="h-[30px] w-[30px]" />
        주문내역
      </button>

      <div className="h-[49px] w-px bg-neutral-200" />

      <button
        type="button"
        onClick={() => navigate('/cart')}
        className="relative flex flex-col items-center gap-1 text-body-2 text-neutral-900"
      >
        <CartIcon className="h-7 w-7" />
        장바구니
        {cartCount > 0 && (
          <span className="absolute -top-1 right-2 flex h-4 w-4 items-center justify-center rounded-full bg-neutral-900 text-[10px] text-neutral-50">
            {cartCount}
          </span>
        )}
      </button>
    </div>
  )
}