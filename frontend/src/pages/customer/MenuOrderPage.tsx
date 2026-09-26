import { useEffect, useMemo, useState } from 'react'
import CategoryTabs, { type MenuCategory } from '../../components/customer/CategoryTabs'
import CustomerBottomNav from '../../components/customer/CustomerBottomNav'
import CustomerTopBar from '../../components/customer/CustomerTopBar'
import MenuListItem from '../../components/customer/MenuListItem'
import StaffCallConfirmModal from '../../components/customer/StaffCallConfirmModal'
import { useCart } from '../../context/CartContext'
import { customerApiFetch } from '../../lib/customerApiFetch'
import { getSessionInfo } from '../../lib/customerSession'
import { requestStaffCall } from '../../lib/staffCall'
import type { CustomerMenuItem } from '../../types/customer'

type MenuBoardResponse = { boothName: string; isOpen: boolean; menus: CustomerMenuItem[] }

// '전체' 탭에서 메인메뉴 → 사이드 → 음료 순으로 보여준다. 분류 없는 메뉴는 맨 뒤
const CATEGORY_ORDER: Record<string, number> = { MAIN: 0, SIDE: 1, DRINK: 2 }

export default function MenuOrderPage() {
  const sessionInfo = getSessionInfo()
  const { addItem, totalQty } = useCart()

  const [menus, setMenus] = useState<CustomerMenuItem[]>([])
  const [boothName, setBoothName] = useState(sessionInfo?.boothName ?? '')
  const [isOpen, setIsOpen] = useState(sessionInfo?.boothIsOpen ?? true)
  const [category, setCategory] = useState<MenuCategory>('ALL')
  const [error, setError] = useState<string | null>(null)
  const [callMessage, setCallMessage] = useState<string | null>(null)
  const [showCallConfirm, setShowCallConfirm] = useState(false)

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

  const visibleMenus = useMemo(() => {
    if (category !== 'ALL') return menus.filter((menu) => menu.category === category)
    // Array.sort는 안정 정렬이라 같은 분류 안에서의 원래 순서는 그대로 유지된다
    return [...menus].sort(
      (a, b) => (CATEGORY_ORDER[a.category ?? ''] ?? 99) - (CATEGORY_ORDER[b.category ?? ''] ?? 99),
    )
  }, [menus, category])

  // C6 직원 호출 — 세션은 customerApiFetch가 X-Session-Token 헤더로 실어 보낸다 (410이면 거기서 재스캔 화면으로 이동)
  // 실수로 눌러도 바로 호출되지 않게 재확인 팝업(Figma 289:4115)을 한 번 거친다
  const handleCallStaff = async () => {
    setShowCallConfirm(false)
    const message = await requestStaffCall('HELP')
    if (message) setCallMessage(message)
  }

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
          <MenuListItem key={menu.id} menu={menu} orderingDisabled={!isOpen} onAdd={() => addItem(menu)} />
        ))}
        {visibleMenus.length === 0 && !error && (
          <p className="py-20 text-center text-body-1 text-neutral-400">메뉴가 없어요.</p>
        )}
      </div>

      <CustomerBottomNav cartCount={totalQty} onCallStaff={() => setShowCallConfirm(true)} />

      {showCallConfirm && (
        <StaffCallConfirmModal onConfirm={handleCallStaff} onCancel={() => setShowCallConfirm(false)} />
      )}
    </div>
  )
}