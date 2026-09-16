import { useEffect, useMemo, useState } from 'react'
import CategoryTabs, { type MenuCategory } from '../../components/customer/CategoryTabs'
import CustomerBottomNav from '../../components/customer/CustomerBottomNav'
import CustomerTopBar from '../../components/customer/CustomerTopBar'
import MenuListItem from '../../components/customer/MenuListItem'
import { useCart } from '../../context/CartContext'
import { readApiError } from '../../lib/apiError'
import { customerApiFetch } from '../../lib/customerApiFetch'
import { getSessionInfo, getSessionToken } from '../../lib/customerSession'
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

  const visibleMenus = useMemo(
    () => (category === 'ALL' ? menus : menus.filter((menu) => menu.category === category)),
    [menus, category],
  )

  // C6 직원 호출 — 세션은 customerApiFetch가 X-Session-Token 헤더로 실어 보낸다 (410이면 거기서 재스캔 화면으로 이동)
  const handleCallStaff = async () => {
    try {
      const res = await customerApiFetch('/api/v1/calls', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reason: 'HELP' }),
      })
      if (res.ok) {
        setCallMessage('직원을 호출했어요.')
        return
      }
      const { code, details } = await readApiError(res)
      if (res.status === 429 && code === 'CALL_COOLDOWN') {
        // 같은 세션 30초 내 재호출 제한 — 남은 시간은 details.retryAfterSeconds
        const seconds = typeof details?.retryAfterSeconds === 'number' ? details.retryAfterSeconds : null
        setCallMessage(seconds ? `이미 호출했어요. ${seconds}초 뒤에 다시 호출할 수 있어요.` : '이미 호출했어요. 잠시 뒤 다시 시도해주세요.')
        return
      }
      setCallMessage(`직원 호출에 실패했어요 (${res.status}). 직원에게 직접 말씀해주세요.`)
    } catch {
      // 410으로 세션이 지워진 경우엔 이미 재스캔 화면으로 이동 중 — 문구를 덧그리지 않는다
      if (!getSessionToken()) return
      setCallMessage('서버에 연결할 수 없어요. 네트워크 상태를 확인해주세요.')
    }
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

      <CustomerBottomNav cartCount={totalQty} onCallStaff={handleCallStaff} />
    </div>
  )
}