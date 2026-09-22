import { useState } from 'react'
import PrimaryButton from '../../components/PrimaryButton'
import SectionHeader from '../../components/SectionHeader'
import TextField from '../../components/TextField'
import TopNav from '../../components/TopNav'
import { apiFetch } from '../../lib/apiFetch'

/** Content-Disposition의 filename="..." 값을 뽑는다 — 못 찾으면 대체 파일명 사용 */
function extractFilename(header: string | null) {
  const match = header?.match(/filename="([^"]+)"/)
  return match?.[1] ?? 'settlement.csv'
}

export default function SettlementPage() {
  const [startAt, setStartAt] = useState('')
  const [endAt, setEndAt] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleDownload = async () => {
    if (loading) return
    if (!startAt || !endAt) {
      setError('시작 일시와 마감 일시를 모두 입력해주세요.')
      return
    }
    if (startAt >= endAt) {
      setError('시작 일시는 마감 일시보다 이전이어야 해요.')
      return
    }
    setLoading(true)
    setError(null)

    try {
      const params = new URLSearchParams({ startAt, endAt })
      const res = await apiFetch(`/api/v1/admin/reports/settlement.csv?${params.toString()}`)
      if (!res.ok) {
        if (res.status === 403) throw new Error('정산 CSV는 ADMIN 계정만 다운로드할 수 있어요.')
        if (res.status === 400) throw new Error('시작/마감 일시를 확인해주세요.')
        throw new Error(`다운로드에 실패했어요 (${res.status})`)
      }
      const blob = await res.blob()
      const filename = extractFilename(res.headers.get('Content-Disposition'))
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
          label="시작 일시"
          type="datetime-local"
          value={startAt}
          onChange={(e) => setStartAt(e.target.value)}
        />
        <TextField
          label="마감 일시"
          type="datetime-local"
          value={endAt}
          onChange={(e) => setEndAt(e.target.value)}
        />
        <p className="text-sm text-neutral-400">
          선택한 시간에 결제 완료된 주문만 CSV에 포함돼요. 
          자정을 넘는 구간도 그대로 입력하면 돼요(예: 시작 9/30 22:00, 마감 10/1 03:00).
        </p>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <PrimaryButton type="button" onClick={handleDownload} disabled={loading} className="disabled:opacity-40">
          {loading ? '다운로드 중...' : 'CSV 다운로드'}
        </PrimaryButton>
      </div>
    </div>
  )
}
