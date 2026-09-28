import { useEffect, useState } from 'react'
import { apiFetch } from '../../lib/apiFetch'

type BoothInfo = { seatFeePerPerson: number }
type ErrorBody = { error?: { message?: string } }

/** 서버 상한 — 백엔드 BoothSettingsService.SEAT_FEE_MAX와 같은 값 */
const MAX_SEAT_FEE = 100_000

/**
 * 자릿세(자동 청구) 1인당 금액 — 설정 "기타 항목 관리" 맨 위 고정 카드 (O16 조회 + O17 `seatFeePerPerson`, 명세서 밖).
 *
 * 손님이 인원을 고르고 첫 메뉴 주문을 넣을 때 "1인당 금액 × 인원수" 자릿세 주문이 따로 함께 올라간다. 0원이면 받지 않는다.
 * 추가 자릿세·쿠폰 같은 기타 항목과 한 화면에서 관리하지만, 저장은 부스 설정이다 — 자동 청구·이중 청구 방지가 이 값과
 * 자릿세 항목 종류(SEAT_FEE)를 기준으로 돌아가서, 지울 수 있는 기타 항목으로 두지 않는다.
 * 손님 청구 금액이라 계좌처럼 ADMIN만 바꿀 수 있다(서버 403). STAFF는 금액을 보기만 한다.
 */
export default function SeatFeeSetting() {
  const [fee, setFee] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    apiFetch('/api/v1/admin/booth')
      .then((res) => {
        if (!res.ok) throw new Error(`자릿세를 불러오지 못했어요 (${res.status})`)
        return res.json()
      })
      .then((data: BoothInfo) => setFee(String(data.seatFeePerPerson ?? 0)))
      .catch((err) => setError(err instanceof Error ? err.message : '자릿세를 불러오지 못했어요.'))
      .finally(() => setLoading(false))
  }, [])

  const handleSave = async () => {
    if (saving) return
    setError(null)
    setSaved(false)

    const value = Number(fee)
    if (fee.trim() === '' || !Number.isInteger(value) || value < 0 || value > MAX_SEAT_FEE) {
      setError(`0원에서 ${MAX_SEAT_FEE.toLocaleString()}원 사이 숫자로 입력해주세요.`)
      return
    }

    setSaving(true)
    try {
      const res = await apiFetch('/api/v1/admin/booth', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ seatFeePerPerson: value }),
      })
      if (!res.ok) {
        const body = (await res.json().catch(() => null)) as ErrorBody | null
        throw new Error(
          res.status === 403
            ? '자릿세는 관리자 계정만 바꿀 수 있어요.'
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
        <span className="text-[22px] leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">자릿세</span>
        <span className="text-sm text-neutral-400">자동 청구 · 1인당</span>
      </div>
      <div className="flex items-center gap-3">
        <input
          aria-label="1인당 자릿세 (원)"
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
      <p className="text-sm text-neutral-400">
        손님이 인원을 고르고 첫 주문을 넣을 때 테이블 주문내역에 따로 함께 올라가요. 0원이면 받지 않아요. 인원이 틀리면 테이블 결제 화면에서
        자릿세 줄을 +/-로 고치고, 취소하면 면제돼요.
      </p>
      {error && <p className="text-sm text-red-600">{error}</p>}
      {saved && !error && <p className="text-sm text-neutral-500">저장됐어요.</p>}
    </div>
  )
}
