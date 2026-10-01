import { useEffect, useMemo, useState } from 'react'
import AddedToCartToast from '../../components/customer/AddedToCartToast'
import CategoryTabs, { type MenuCategory } from '../../components/customer/CategoryTabs'
import CustomerBottomNav from '../../components/customer/CustomerBottomNav'
import CustomerTopBar from '../../components/customer/CustomerTopBar'
import MenuListItem from '../../components/customer/MenuListItem'
import StaffCallConfirmModal from '../../components/customer/StaffCallConfirmModal'
import { useCart } from '../../context/CartContext'
import { customerApiFetch } from '../../lib/customerApiFetch'
import { sortMenusForBoard } from '../../lib/menuOrder'
import { getSessionInfo } from '../../lib/customerSession'
import { useAddedToCartToast } from '../../lib/useAddedToCartToast'
import { useStaffCallModal } from '../../lib/useStaffCallModal'
import type { CustomerMenuItem } from '../../types/customer'

type MenuBoardResponse = { boothName: string; isOpen: boolean; menus: CustomerMenuItem[] }

export default function MenuOrderPage() {
  const sessionInfo = getSessionInfo()
  const { addItem, totalQty } = useCart()
  // 배지 숫자만으로는 육안으로 담겼는지 확인하기 어렵다는 피드백(2026-09-30) — 담을 때마다 잠깐 확인 토스트를 띄운다
  const { entry: addedEntry, show: showAdded } = useAddedToCartToast()

  const [menus, setMenus] = useState<CustomerMenuItem[]>([])
  const [boothName, setBoothName] = useState(sessionInfo?.boothName ?? '')
  const [isOpen, setIsOpen] = useState(sessionInfo?.boothIsOpen ?? true)
  const [category, setCategory] = useState<MenuCategory>('ALL')
  const [error, setError] = useState<string | null>(null)
  const { callMessage, showCallConfirm, openCallConfirm, closeCallConfirm, handleCallStaff } = useStaffCallModal('HELP')

  useEffect(() => {
    let cancelled = false
    customerApiFetch('/api/v1/menus')
      .then((res) => {
        if (!res.ok) throw new Error(`메뉴판을 불러오지 못했어요 (${res.status})`)
        return res.json() as Promise<MenuBoardResponse>
      })
      .then((data) => {
        if (cancelled) return
        setMenus(data.menus)
        setBoothName(data.boothName)
        setIsOpen(data.isOpen)
        setError(null)
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : '메뉴판을 불러오지 못했어요.')
      })
    return () => {
      cancelled = true
    }
  }, [])

  // 분류(메인 → 사이드 → 음료) 안에서 가격 높은 순 — 규칙은 lib/menuOrder
  const visibleMenus = useMemo(
    () => sortMenusForBoard(menus, category === 'ALL' ? undefined : category),
    [menus, category],
  )

  return (
    <div className="min-h-screen w-full bg-neutral-50 pb-[97px]">
      <CustomerTopBar boothName={boothName} tableLabel={sessionInfo?.tableLabel ?? ''} />

      {!isOpen && (
        <p className="bg-neutral-100 px-5 py-2 text-center text-body-3 text-neutral-600">
          지금은 주문 접수 시간이 아니에요. 메뉴만 둘러보실 수 있어요.
        </p>
      )}

      <CategoryTabs active={category} onChange={setCategory} />
      <div className="h-px bg-neutral-100" />

      {error && <p className="px-6 pt-4 text-body-3 text-red-600">{error}</p>}
      {callMessage && <p className="px-6 pt-2 text-center text-body-3 text-neutral-400">{callMessage}</p>}

      <div className="flex flex-col gap-4 px-6 py-5">
        {visibleMenus.map((menu) => (
          <MenuListItem
            key={menu.id}
            menu={menu}
            orderingDisabled={!isOpen}
            onAdd={() => {
              addItem(menu)
              showAdded(`${menu.name} 담았어요`)
            }}
          />
        ))}
        {visibleMenus.length === 0 && !error && (
          <p className="py-20 text-center text-body-1 text-neutral-400">메뉴가 없어요.</p>
        )}
      </div>

      <CustomerBottomNav cartCount={totalQty} onCallStaff={openCallConfirm} />
      <AddedToCartToast entry={addedEntry} />

      {showCallConfirm && (
        <StaffCallConfirmModal onConfirm={handleCallStaff} onCancel={closeCallConfirm} />
      )}
    </div>
  )
}