import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import type { CartItem, CustomerMenuItem } from '../types/customer'

type CartContextValue = {
  items: CartItem[]
  totalQty: number
  totalAmount: number
  addItem: (menu: CustomerMenuItem) => void
  updateQty: (menuId: number, qty: number) => void
  removeItem: (menuId: number) => void
  clear: () => void
}

const CartContext = createContext<CartContextValue | null>(null)

export function CartProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<CartItem[]>([])

  const addItem: CartContextValue['addItem'] = (menu) => {
    setItems((prev) => {
      const existing = prev.find((i) => i.menuId === menu.id)
      if (existing) {
        return prev.map((i) => (i.menuId === menu.id ? { ...i, qty: i.qty + 1 } : i))
      }
      return [...prev, { menuId: menu.id, name: menu.name, unitPrice: menu.price, imageUrl: menu.imageUrl, qty: 1 }]
    })
  }

  // qty가 0 이하가 되면 장바구니에서 제거 (- 버튼을 1개 상태에서 더 누르면 삭제되는 흔한 패턴)
  const updateQty: CartContextValue['updateQty'] = (menuId, qty) => {
    setItems((prev) =>
      qty <= 0 ? prev.filter((i) => i.menuId !== menuId) : prev.map((i) => (i.menuId === menuId ? { ...i, qty } : i)),
    )
  }

  const removeItem: CartContextValue['removeItem'] = (menuId) => {
    setItems((prev) => prev.filter((i) => i.menuId !== menuId))
  }

  const clear = () => setItems([])

  const totalQty = useMemo(() => items.reduce((sum, i) => sum + i.qty, 0), [items])
  const totalAmount = useMemo(() => items.reduce((sum, i) => sum + i.unitPrice * i.qty, 0), [items])

  return (
    <CartContext.Provider value={{ items, totalQty, totalAmount, addItem, updateQty, removeItem, clear }}>
      {children}
    </CartContext.Provider>
  )
}

export function useCart() {
  const ctx = useContext(CartContext)
  if (!ctx) throw new Error('useCart는 CartProvider 안에서만 쓸 수 있어요')
  return ctx
}