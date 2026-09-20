import { useState } from 'react'
import PrimaryButton from '../../components/PrimaryButton'
import SectionHeader from '../../components/SectionHeader'
import TextField from '../../components/TextField'
import TopNav from '../../components/TopNav'
import { apiFetch } from '../../lib/apiFetch'

/** 오늘(브라우저 로컬 날짜)을 input[type=date] 형식(yyyy-MM-dd)으로 — O19 기본값(현재 영업일)과 정확히 맞을 필요는 없고 사용자가 직접 바꿀 수 있음 */
function todayInputValue() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

/** Content-Disposition의 filename="..." 값을 뽑는다 — 못 찾으면 날짜로 대체 파일명 구성 */
function extractFilename(header: string | null, fallbackDate: string) {
  const match = header?.match(/filename="([^"]+)"/)
  return match?.[1] ?? `settlement_${fallbackDate}.csv`
}

export default function SettlementPage() {
  const [date, setDate] = useState(todayInputValue)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleDownload = async () => {
    if (loading) return
    setLoading(true)
    setError(null)

    try {
      const res = await apiFetch(`/api/v1/admin/reports/settlement.csv?date=${date}`)
      if (!res.ok) {
        if (res.status === 403) throw new Error('정산 CSV는 ADMIN 계정만 다운로드할 수 있어요.')
        throw new Error(`다운로드에 실패했어요 (${res.status})`)
      }
      const blob = await res.blob()
      const filename = extractFilename(res.headers.get('Content-Disposition'), date)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      // DOM에 붙였다 떼기 — 문서에 없는 앵커의 클릭은 일부 브라우저/자동화 환경에서 다운로드로 안 이어질 수 있다(실측)
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
    } catch (err) {
      setError(err instanceof Error ? err.message : '다운로드에 실패했어요.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />
      <SectionHeader title="정산 CSV 다운로드" />

      <div className="mx-auto flex w-full max-w-[600px] flex-col gap-6 px-6 py-10">
        <TextField
          label="영업일"
          type="date"
          value={date}
          onChange={(e) => setDate(e.target.value)}
        />
        <p className="text-sm text-neutral-400">
          영업일은 06:00부터 다음날 05:59까지예요. 선택한 날짜의 전체 주문 항목을 CSV로 받아요.
        </p>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <PrimaryButton type="button" onClick={handleDownload} disabled={loading} className="disabled:opacity-40">
          {loading ? '다운로드 중...' : 'CSV 다운로드'}
        </PrimaryButton>
      </div>
    </div>
  )
}
