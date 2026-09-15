import { useEffect, useMemo, useState } from 'react'
import CategoryTabs, { type MenuCategory } from '../../components/customer/CategoryTabs'
import CustomerBottomNav from '../../components/customer/CustomerBottomNav'
import CustomerTopBar from '../../components/customer/CustomerTopBar'
import MenuListItem from '../../components/customer/MenuListItem'
import { useCart } from '../../context/CartContext'
import { customerApiFetch } from '../../lib/customerApiFetch'
import { getSessionInfo } from '../../lib/customerSession'
import type { CustomerMenuItem } from '../../types/customer'

type MenuBoardResponse = { boothName: string; isOpen: boolean; menus: CustomerMenuItem[] }

export default function MenuOrderPage() {
  const sessionInfo = getSessionInfo()
  const { addItem, totalQty } = useCart()

  const [menus, setMenus] = useState<CustomerMenuItem[]>([])
  const [boothName, setBoothName] = useState(sessionInfo?.boothName ?? '')
  const [isOpen, setIsOpen] = useState(sessionInfo?.boothIsOpen ?? true)
  const [category, setCategory] = useState<MenuCategory>('ALL')
  const [error, setError] = useState<string | null>(null)
  const [callMessage, setCallMessage] = useState<string | null>(null)

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

  // category는 백엔드 C2 응답에 아직 없는 필드 — 값이 없는 메뉴는 '전체'에서만 노출된다 (types/customer.ts 참고)
  const visibleMenus = useMemo(
    () => (category === 'ALL' ? menus : menus.filter((menu) => menu.category === category)),
    [menus, category],
  )

  // C6(직원 호출)는 백엔드에 아직 구현되어 있지 않음 — 구현되면 catch의 안내 문구를 제거
  const handleCallStaff = async () => {
    try {
      const res = await customerApiFetch('/api/v1/calls', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reason: 'HELP' }),
      })
      if (!res.ok) throw new Error()
      setCallMessage('직원을 호출했어요.')
    } catch {
      setCallMessage('호출 기능은 아직 준비 중이에요.')
    }
  }

  return (
    <div className="min-h-screen w-full bg-white pb-24">
      <CustomerTopBar boothName={boothName} tableLabel={sessionInfo?.tableLabel ?? ''} />

      {!isOpen && (
        <p className="bg-neutral-100 px-5 py-2 text-center text-body-3 text-neutral-600">
          지금은 주문 접수 시간이 아니에요. 메뉴만 둘러보실 수 있어요.
        </p>
      )}

      <CategoryTabs active={category} onChange={setCategory} />

      {error && <p className="px-5 pb-2 text-body-3 text-red-600">{error}</p>}
      {callMessage && <p className="px-5 pb-2 text-center text-body-3 text-neutral-400">{callMessage}</p>}

      <div>
        {visibleMenus.map((menu) => (
          <MenuListItem key={menu.id} menu={menu} orderingDisabled={!isOpen} onAdd={() => addItem(menu)} />
        ))}
        {visibleMenus.length === 0 && !error && (
          <p className="py-20 text-center text-body-1 text-neutral-400">메뉴가 없어요.</p>
        )}
      </div>

      <CustomerBottomNav cartCount={totalQty} onCallStaff={handleCallStaff} />
    </div>
  )
}