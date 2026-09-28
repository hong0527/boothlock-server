import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { CART_STORAGE_KEY } from '../lib/customerSession'
import { readStoredJson, writeStored } from '../lib/safeStorage'
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
  // 저장소에서 이어받는다 — 안드로이드 크롬이 백그라운드 탭을 통째로 새로고침해도(QR을 카메라로 찍으러
  // 나갔다 오는 사이 실제로 겪음) 담아둔 메뉴가 날아가지 않는다. 세션이 바뀌면(퇴실 뒤 재스캔 등) customerSession.ts가
  // 이 저장소를 먼저 비운다 — 앞 손님 장바구니가 다음 손님에게 보이지 않는다
  const [items, setItems] = useState<CartItem[]>(() => readStoredJson<CartItem[]>(CART_STORAGE_KEY) ?? [])

  useEffect(() => {
    writeStored(CART_STORAGE_KEY, JSON.stringify(items))
  }, [items])

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