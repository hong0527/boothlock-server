import { useNavigate } from 'react-router-dom'
import BackButton from '../../components/customer/BackButton'
import { CUSTOMER_BUTTON_BASE } from '../../components/controlStyles'
import { useCart } from '../../context/CartContext'

export default function CartPage() {
  const navigate = useNavigate()
  const { items, totalAmount, updateQty, removeItem } = useCart()

  return (
    <div className="flex min-h-screen w-full flex-col bg-neutral-50">
      <div className="flex h-[114px] items-center bg-primary-50 px-4">
        <BackButton />
        <h1 className="ml-3 text-heading-1 text-neutral-900">장바구니</h1>
      </div>

      <div className="flex-1 overflow-y-auto px-6">
        {items.map((item) => (
          <div key={item.menuId} className="flex items-start gap-[19px] border-b border-neutral-100 py-6">
            <div className="h-20 w-20 shrink-0 rounded-[12px] bg-[#d9d9d9]" />
            <div className="flex flex-1 flex-col justify-between self-stretch">
              <div className="flex items-start justify-between">
                <span className="text-body-1 text-neutral-900">{item.name}</span>
                <button
                  type="button"
                  onClick={() => removeItem(item.menuId)}
                  aria-label={`${item.name} 삭제`}
                  className="text-neutral-900"
                >
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M6 6l12 12M18 6L6 18" strokeLinecap="round" />
                  </svg>
                </button>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-heading-3 text-neutral-700">{item.unitPrice.toLocaleString()}원</span>
                <div className="flex h-[31px] items-center gap-2 rounded-[20px] bg-neutral-200 px-3">
                  <button
                    type="button"
                    onClick={() => updateQty(item.menuId, item.qty - 1)}
                    aria-label="수량 줄이기"
                    className="text-neutral-900"
                  >
                    −
                  </button>
                  <span className="w-4 text-center text-body-2 text-neutral-900">{item.qty}</span>
                  <button
                    type="button"
                    onClick={() => updateQty(item.menuId, item.qty + 1)}
                    aria-label="수량 늘리기"
                    className="text-neutral-900"
                  >
                    +
                  </button>
                </div>
              </div>
            </div>
          </div>
        ))}
        {items.length === 0 && (
          <p className="py-20 text-center text-body-1 text-neutral-400">장바구니가 비어있어요.</p>
        )}
      </div>

      <div className="border-t border-neutral-200 bg-neutral-50 px-6 pt-5 pb-8">
        <div className="mb-4 flex items-center justify-between">
          <span className="text-body-1 text-neutral-900">총 주문금액</span>
          <span className="text-heading-2 text-neutral-900">{totalAmount.toLocaleString()}원</span>
        </div>
        <button
          type="button"
          disabled={items.length === 0}
          onClick={() => navigate('/order-confirm')}
          className={`${CUSTOMER_BUTTON_BASE} bg-black text-heading-3 text-white disabled:opacity-40`}
        >
          주문하기
        </button>
      </div>
    </div>
  )
}