import { useEffect, useState } from 'react'
import { apiFetch } from '../../lib/apiFetch'

type ErrorBody = { error?: { message?: string } }

type Props = {
  /** O16 응답·O17 요청의 필드 이름 */
  field: 'seatFeePerPerson' | 'minOrderAmount'
  title: string
  caption: string
  /** 서버 상한 — 백엔드 BoothSettingsService의 같은 이름 상수와 맞춘다 */
  max: number
  description: string
}

/**
 * 부스 설정의 금액 한 칸 — 설정 "자릿세 · 기타 항목 관리" 맨 위 고정 카드 (O16 조회 + O17, 명세서 밖).
 * 자릿세 1인당 금액(seatFeePerPerson)과 첫 주문 최소금액(minOrderAmount)이 쓴다. 0원이면 안 받음/제한 없음.
 *
 * 기타 항목과 한 화면에서 관리하지만 저장은 부스 설정이다 — 자릿세는 자동 청구·이중 청구 방지가 이 값과
 * 자릿세 항목 종류(SEAT_FEE)를 기준으로 돌아가서, 지울 수 있는 기타 항목으로 두지 않는다.
 * 손님 청구·주문에 걸리는 금액이라 계좌처럼 ADMIN만 바꿀 수 있다(서버 403). STAFF는 금액을 보기만 한다.
 */
export default function BoothAmountSetting({ field, title, caption, max, description }: Props) {
  const [fee, setFee] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    apiFetch('/api/v1/admin/booth')
      .then((res) => {
        if (!res.ok) throw new Error(`${title} 설정을 불러오지 못했어요 (${res.status})`)
        return res.json()
      })
      .then((data: Partial<Record<Props['field'], number>>) => setFee(String(data[field] ?? 0)))
      .catch((err) => setError(err instanceof Error ? err.message : `${title} 설정을 불러오지 못했어요.`))
      .finally(() => setLoading(false))
  }, [field, title])

  const handleSave = async () => {
    if (saving) return
    setError(null)
    setSaved(false)

    const value = Number(fee)
    if (fee.trim() === '' || !Number.isInteger(value) || value < 0 || value > max) {
      setError(`0원에서 ${max.toLocaleString()}원 사이 숫자로 입력해주세요.`)
      return
    }

    setSaving(true)
    try {
      const res = await apiFetch('/api/v1/admin/booth', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ [field]: value }),
      })
      if (!res.ok) {
        const body = (await res.json().catch(() => null)) as ErrorBody | null
        throw new Error(
          res.status === 403
            ? `${title} 설정은 관리자 계정만 바꿀 수 있어요.`
            : (body?.error?.message ?? `저장에 실패했어요 (${res.status})`),
        )
      }
      setFee(String(value))
      setSaved(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : '저장에 실패했어요.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-neutral-200 bg-neutral-50 px-5 py-5">
      <div className="flex items-baseline justify-between">
        <span className="text-[22px] leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">{title}</span>
        <span className="text-sm text-neutral-400">{caption}</span>
      </div>
      <div className="flex items-center gap-3">
        <input
          aria-label={`${title} (원)`}
          inputMode="numeric"
          value={fee}
          disabled={loading}
          onChange={(e) => {
            // 숫자만 받는다 — 쉼표·공백을 붙여 넣어도 숫자만 남긴다
            setFee(e.target.value.replace(/[^0-9]/g, ''))
            setSaved(false)
            setError(null)
          }}
          className="h-12 min-w-0 flex-1 rounded-xl border border-neutral-200 bg-white px-4 text-lg font-semibold text-neutral-900"
        />
        <span className="text-lg font-semibold text-neutral-900">원</span>
        <button
          type="button"
          onClick={handleSave}
          disabled={loading || saving}
          className="h-12 shrink-0 rounded-xl bg-neutral-900 px-5 text-base font-semibold text-neutral-50 disabled:opacity-40"
        >
          {saving ? '저장 중...' : '저장'}
        </button>
      </div>
      <p className="text-sm text-neutral-400">{description}</p>
      {error && <p className="text-sm text-red-600">{error}</p>}
      {saved && !error && <p className="text-sm text-neutral-500">저장됐어요.</p>}
    </div>
  )
}
