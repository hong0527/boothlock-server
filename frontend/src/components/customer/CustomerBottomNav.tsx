import { useNavigate } from 'react-router-dom'

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
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
          <circle cx="12" cy="8" r="3.2" />
          <path d="M5 20c0-3.5 3.1-6 7-6s7 2.5 7 6" strokeLinecap="round" />
        </svg>
        직원 호출
      </button>

      <div className="h-[49px] w-px bg-neutral-200" />

      <button
        type="button"
        onClick={() => navigate('/order-history')}
        className="flex flex-col items-center gap-1 text-body-2 text-neutral-900"
      >
        <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
          <path d="M6 3h12v18l-3-2-3 2-3-2-3 2V3z" strokeLinejoin="round" />
          <path d="M9 8h6M9 12h6" strokeLinecap="round" />
        </svg>
        주문내역
      </button>

      <div className="h-[49px] w-px bg-neutral-200" />

      <button
        type="button"
        onClick={() => navigate('/cart')}
        className="relative flex flex-col items-center gap-1 text-body-2 text-neutral-900"
      >
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
          <path d="M4 5h2l2 11h10l2-8H7" strokeLinecap="round" strokeLinejoin="round" />
          <circle cx="10" cy="19" r="1.4" />
          <circle cx="17" cy="19" r="1.4" />
        </svg>
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