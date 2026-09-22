import { useState } from 'react'
import PrimaryButton from '../../components/PrimaryButton'
import SectionHeader from '../../components/SectionHeader'
import TextField from '../../components/TextField'
import TopNav from '../../components/TopNav'
import { apiFetch } from '../../lib/apiFetch'

const KST_OFFSET_MS = 9 * 60 * 60 * 1000
const BUSINESS_DAY_START_HOURS = 6

/** 날짜 문자열(yyyy-MM-dd)에 일수를 더한다 — 달력 계산만 필요해 UTC로 계산해 타임존 흔들림을 피한다 */
function addDays(dateOnly: string, days: number) {
  const [y, m, d] = dateOnly.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10)
}

/**
 * 기본값 = "현재 영업일 06:00 ~ 다음날 06:00" (datetime-local 형식 yyyy-MM-ddTHH:mm).
 * 영업일은 06:00에 바뀌므로(서버 OrderNumberingService와 같은 규칙) 자정 넘어 켜면 아직 시작 전인
 * 다음 영업일이 아니라 방금 지난 영업일이 기본으로 잡혀야 한다 — 브라우저 시계를 KST로 맞춘 뒤 6시간을 뺀다.
 * 어디까지나 입력칸의 초기값일 뿐, 사용자가 자유롭게 바꿔 자정을 넘는 임의 구간도 입력할 수 있다.
 */
export function defaultRange(at: Date = new Date()) {
  const kst = new Date(at.getTime() + KST_OFFSET_MS - BUSINESS_DAY_START_HOURS * 60 * 60 * 1000)
  const businessDate = kst.toISOString().slice(0, 10)
  return {
    startAt: `${businessDate}T06:00`,
    endAt: `${addDays(businessDate, 1)}T06:00`,
  }
}

/** Content-Disposition의 filename="..." 값을 뽑는다 — 못 찾으면 구간으로 대체 파일명 구성 */
function extractFilename(header: string | null, startAt: string, endAt: string) {
  const match = header?.match(/filename="([^"]+)"/)
  return match?.[1] ?? `settlement_${startAt}_${endAt}.csv`
}

export default function SettlementPage() {
  const [{ startAt, endAt }, setRange] = useState(defaultRange)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleDownload = async () => {
    if (loading) return
    setError(null)

    if (!startAt || !endAt) {
      setError('시작 일시와 마감 일시를 모두 입력해주세요.')
      return
    }
    if (!(startAt < endAt)) {
      setError('마감 일시는 시작 일시보다 이후여야 해요.')
      return
    }

    setLoading(true)
    try {
      // datetime-local 값을 UTC 변환 없이 그대로 query parameter로 보낸다 — 서버가 KST로 그대로 해석
      const params = new URLSearchParams({ startAt, endAt })
      const res = await apiFetch(`/api/v1/admin/reports/settlement.csv?${params.toString()}`)
      if (!res.ok) {
        if (res.status === 403) throw new Error('정산 CSV는 ADMIN 계정만 다운로드할 수 있어요.')
        if (res.status === 400) throw new Error('시작/마감 일시를 다시 확인해주세요.')
        throw new Error(`다운로드에 실패했어요 (${res.status})`)
      }
      const blob = await res.blob()
      const filename = extractFilename(res.headers.get('Content-Disposition'), startAt, endAt)
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
          onChange={(e) => setRange((prev) => ({ ...prev, startAt: e.target.value }))}
        />
        <TextField
          label="마감 일시"
          type="datetime-local"
          value={endAt}
          onChange={(e) => setRange((prev) => ({ ...prev, endAt: e.target.value }))}
        />
        <p className="text-sm text-neutral-400">
          입력한 시작 일시부터 마감 일시까지의 주문 내역을 CSV로 다운로드합니다. (예: 9/30 22:00 ~ 10/1 03:00)
          <br />
          시작 일시는 포함, 마감 일시는 포함하지 않아요. 날짜가 바뀌는 시간 범위도 그대로 입력할 수 있어요.
        </p>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <PrimaryButton type="button" onClick={handleDownload} disabled={loading} className="disabled:opacity-40">
          {loading ? '다운로드 중...' : 'CSV 다운로드'}
        </PrimaryButton>
      </div>
    </div>
  )
}
