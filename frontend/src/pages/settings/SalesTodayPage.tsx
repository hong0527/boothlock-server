import { useCallback, useEffect, useState } from 'react'
import PrimaryButton from '../../components/PrimaryButton'
import SectionHeader from '../../components/SectionHeader'
import TopNav from '../../components/TopNav'
import { fetchSalesStats, splitItemSales, type ItemSales, type SalesStats } from '../../lib/salesStats'

const won = (value: number) => `${value.toLocaleString('ko-KR')}원`

/**
 * 오늘(현재 영업일) 메뉴별 판매 개수 — O18 매출 집계를 그대로 쓴다(ADMIN 전용).
 * 영업일은 06:00 KST에 바뀐다(서버 OrderNumberingService와 같은 규칙) — 자정을 넘긴 주문도 같은 날로 묶인다.
 * 결제완료 주문의 미취소 항목만 센다. 메뉴와 자릿세·기타 항목(쿠폰 등, 음수 가능)은 표를 나눠 보여 준다.
 */
export default function SalesTodayPage() {
  const [stats, setStats] = useState<SalesStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // 첫 진입·새로고침이 같이 쓴다 — 상태 변경은 응답 뒤에만 해서 effect 안에서 동기 setState를 하지 않는다
  const fetchStats = useCallback(
    () =>
      fetchSalesStats()
        .then((data) => {
          setStats(data)
          setError(null)
        })
        .catch((err) => setError(err instanceof Error ? err.message : '판매 현황을 불러오지 못했어요.'))
        .finally(() => setLoading(false)),
    [],
  )

  useEffect(() => {
    fetchStats()
  }, [fetchStats])

  const handleRefresh = () => {
    if (loading) return
    setLoading(true)
    fetchStats()
  }

  const { menus, extras, menuQty } = splitItemSales(stats?.itemSales ?? [])

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />
      <SectionHeader title="오늘 판매 현황" />

      <div className="mx-auto flex w-full max-w-[600px] flex-col gap-6 px-6 py-10">
        {error && <p className="text-sm text-red-600">{error}</p>}

        {stats && (
          <>
            <div className="flex flex-col gap-1">
              <p className="text-sm text-neutral-500">
                {stats.businessDate} 06:00 ~ 다음날 06:00 · 결제완료 기준
              </p>
              <p className="text-[22px] font-semibold tracking-[-0.04em] text-neutral-900">
                메뉴 {menuQty.toLocaleString('ko-KR')}개 · {won(stats.totalSales)}
              </p>
              <p className="text-sm text-neutral-500">결제완료 주문 {stats.paidOrderCount}건 · 매출은 자릿세·기타 항목 포함</p>
            </div>

            {menus.length === 0 ? (
              <p className="text-neutral-500">아직 오늘 결제완료된 메뉴가 없어요.</p>
            ) : (
              <SalesTable title="메뉴" rows={menus} />
            )}
            {extras.length > 0 && <SalesTable title="자릿세 · 기타 항목" rows={extras} />}
          </>
        )}

        <PrimaryButton type="button" onClick={handleRefresh} disabled={loading} className="disabled:opacity-40">
          {loading ? '불러오는 중...' : '새로고침'}
        </PrimaryButton>
      </div>
    </div>
  )
}

function SalesTable({ title, rows }: { title: string; rows: ItemSales[] }) {
  return (
    <table className="w-full border-collapse text-left">
      <thead>
        <tr className="border-b border-neutral-200 text-sm text-neutral-500">
          <th className="py-2 font-medium">{title}</th>
          <th className="py-2 text-right font-medium">개수</th>
          <th className="py-2 text-right font-medium">금액</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={`${r.itemType}:${r.name}`} className="border-b border-neutral-100 text-neutral-900">
            <td className="py-3 font-semibold">{r.name}</td>
            <td className="py-3 text-right">{r.qty.toLocaleString('ko-KR')}개</td>
            <td className={`py-3 text-right ${r.amount < 0 ? 'text-red-600' : ''}`}>{won(r.amount)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
