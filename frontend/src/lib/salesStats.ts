import { apiFetch } from './apiFetch'

export type ItemType = 'MENU' | 'SEAT_FEE' | 'EXTRA'
export type ItemSales = { itemType: ItemType; name: string; qty: number; amount: number }

export type SalesStats = {
  businessDate: string
  totalSales: number
  paidOrderCount: number
  itemSales: ItemSales[]
}

/** 메뉴 표와 자릿세·기타 항목 표로 나눈다. 상단 "메뉴 N개"는 메뉴만 센다(자릿세 인원·쿠폰 수는 판매 개수가 아니다) */
export function splitItemSales(itemSales: ItemSales[]) {
  const menus = itemSales.filter((i) => i.itemType === 'MENU')
  const extras = itemSales.filter((i) => i.itemType !== 'MENU')
  return { menus, extras, menuQty: menus.reduce((sum, m) => sum + m.qty, 0) }
}

export async function fetchSalesStats(): Promise<SalesStats> {
  const res = await apiFetch('/api/v1/admin/stats/sales')
  if (!res.ok) {
    if (res.status === 403) throw new Error('판매 현황은 ADMIN 계정만 볼 수 있어요.')
    throw new Error(`판매 현황을 불러오지 못했어요 (${res.status})`)
  }
  return res.json()
}
