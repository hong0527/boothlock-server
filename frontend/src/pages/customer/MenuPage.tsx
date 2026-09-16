import { useMemo, useState } from 'react'
import CategoryTabs, { type MenuCategory } from '../../components/customer/CategoryTabs'
import CustomerBottomNav from '../../components/customer/CustomerBottomNav'
import CustomerHeader from '../../components/customer/CustomerHeader'
import MenuItemCard from '../../components/customer/MenuItemCard'
import StaffCallModal from '../../components/customer/StaffCallModal'
import { customerMenuItems, type CustomerMenuItem } from './menuData'

export default function MenuPage() {
  const [category, setCategory] = useState<MenuCategory>('MAIN')
  const [cartItems, setCartItems] = useState<CustomerMenuItem[]>([])
  const [isStaffCallOpen, setIsStaffCallOpen] = useState(false)

  const visibleItems = useMemo(
    () =>
      category === 'ALL' ? customerMenuItems : customerMenuItems.filter((item) => item.category === category),
    [category],
  )

  const handleAddToCart = (item: CustomerMenuItem) => {
    setCartItems((items) => [...items, item])
  }

  const cartCount = cartItems.length

  return (
    <main className="mx-auto flex min-h-[100dvh] w-full max-w-[600px] flex-col overflow-hidden bg-neutral-50 text-black">
      <CustomerHeader />
      <CategoryTabs value={category} onChange={setCategory} />
      <section className="min-h-0 flex-1 overflow-y-auto pl-6 pr-[23px] pt-3 pb-[121px]" aria-label="메뉴 목록">
        <div className="mx-auto flex w-full max-w-[528px] flex-col gap-[17px]">
          {visibleItems.map((item) => (
            <MenuItemCard key={item.id} item={item} onAdd={handleAddToCart} />
          ))}
        </div>
      </section>
      <CustomerBottomNav
        onStaffCall={() => setIsStaffCallOpen(true)}
        onOrderHistory={() => undefined}
        onCart={() => undefined}
        cartCount={cartCount}
      />
      <StaffCallModal
        open={isStaffCallOpen}
        onClose={() => setIsStaffCallOpen(false)}
        onConfirm={() => setIsStaffCallOpen(false)}
      />
      <span className="sr-only">장바구니에 담긴 메뉴 {cartItems.length}개</span>
    </main>
  )
}
