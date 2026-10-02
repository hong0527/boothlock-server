import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from './apiFetch'
import { fetchSalesStats, splitItemSales, type ItemSales } from './salesStats'

vi.mock('./apiFetch', () => ({ apiFetch: vi.fn() }))

beforeEach(() => {
  vi.clearAllMocks()
})

describe('splitItemSales', () => {
  it('메뉴와 자릿세·기타 항목을 나누고, 상단 개수는 메뉴만 센다', () => {
    const items: ItemSales[] = [
      { itemType: 'MENU', name: '치킨', qty: 3, amount: 60_000 },
      { itemType: 'MENU', name: '콜라', qty: 2, amount: 4_000 },
      { itemType: 'SEAT_FEE', name: '자릿세', qty: 4, amount: 12_000 },
      { itemType: 'EXTRA', name: '쿠폰', qty: 1, amount: -2_000 },
    ]

    const { menus, extras, menuQty } = splitItemSales(items)

    expect(menus.map((m) => m.name)).toEqual(['치킨', '콜라'])
    expect(extras.map((e) => e.name)).toEqual(['자릿세', '쿠폰'])
    expect(menuQty).toBe(5)
  })

  it('항목이 없으면 빈 표와 0개', () => {
    expect(splitItemSales([])).toEqual({ menus: [], extras: [], menuQty: 0 })
  })
})

describe('fetchSalesStats', () => {
  it('날짜 없이 O18을 불러 현재 영업일 집계를 받는다', async () => {
    const body = { businessDate: '2026-10-02', totalSales: 0, paidOrderCount: 0, itemSales: [] }
    vi.mocked(apiFetch).mockResolvedValue(new Response(JSON.stringify(body), { status: 200 }))

    await expect(fetchSalesStats()).resolves.toEqual(body)
    expect(apiFetch).toHaveBeenCalledWith('/api/v1/admin/stats/sales')
  })

  it('403이면 ADMIN 전용 안내', async () => {
    vi.mocked(apiFetch).mockResolvedValue(new Response(null, { status: 403 }))

    await expect(fetchSalesStats()).rejects.toThrow('판매 현황은 ADMIN 계정만 볼 수 있어요.')
  })

  it('그 밖의 실패는 상태 코드를 보여 준다', async () => {
    vi.mocked(apiFetch).mockResolvedValue(new Response(null, { status: 500 }))

    await expect(fetchSalesStats()).rejects.toThrow('판매 현황을 불러오지 못했어요 (500)')
  })
})
