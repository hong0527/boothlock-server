import { useNavigate } from 'react-router-dom'
import PrimaryButton from '../../components/PrimaryButton'
import { useCart } from '../../context/CartContext'

export default function CartPage() {
  const navigate = useNavigate()
  const { items, totalAmount, updateQty, removeItem } = useCart()

  return (
    <div className="flex min-h-screen w-full flex-col bg-white">
      <div className="flex items-center gap-4 border-b border-neutral-100 px-5 py-4">
        <button
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로가기"
          className="text-heading-2 text-neutral-900"
        >
          ‹
        </button>
        <h1 className="text-heading-3 text-neutral-900">장바구니</h1>
      </div>

      <div className="flex-1 overflow-y-auto px-5">
        {items.map((item) => (
          <div key={item.menuId} className="flex items-center gap-4 border-b border-neutral-100 py-4">
            <div className="h-16 w-16 shrink-0 rounded-xl bg-neutral-200" />
            <div className="flex-1">
              <div className="flex items-start justify-between">
                <span className="text-body-1 text-neutral-900">{item.name}</span>
                <button
                  type="button"
                  onClick={() => removeItem(item.menuId)}
                  aria-label={`${item.name} 삭제`}
                  className="text-neutral-400"
                >
                  ×
                </button>
              </div>
              <div className="mt-2 flex items-center justify-between">
                <span className="text-body-1 text-neutral-900">{item.unitPrice.toLocaleString()}원</span>
                <div className="flex items-center gap-3">
                  <button
                    type="button"
                    onClick={() => updateQty(item.menuId, item.qty - 1)}
                    aria-label="수량 줄이기"
                    className="h-7 w-7 rounded-lg border border-neutral-200 text-body-1 text-neutral-900"
                  >
                    −
                  </button>
                  <span className="w-4 text-center text-body-1 text-neutral-900">{item.qty}</span>
                  <button
                    type="button"
                    onClick={() => updateQty(item.menuId, item.qty + 1)}
                    aria-label="수량 늘리기"
                    className="h-7 w-7 rounded-lg border border-neutral-200 text-body-1 text-neutral-900"
                  >
                    +
                  </button>
                </div>
              </div>
            </div>
          </div>
        ))}
        {items.length === 0 && <p className="py-20 text-center text-body-1 text-neutral-400">장바구니가 비어있어요.</p>}
      </div>

      <div className="border-t border-neutral-100 px-5 py-5">
        <div className="mb-4 flex items-center justify-between text-heading-3 text-neutral-900">
          <span>총 주문금액</span>
          <span>{totalAmount.toLocaleString()}원</span>
        </div>
        <PrimaryButton
          type="button"
          disabled={items.length === 0}
          onClick={() => navigate('/order-confirm')}
          className="disabled:opacity-40"
        >
          주문하기
        </PrimaryButton>
      </div>
    </div>
  )
}