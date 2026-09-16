import { useEffect, useState } from 'react'
import PrimaryButton from '../../components/PrimaryButton'
import SectionHeader from '../../components/SectionHeader'
import TextField from '../../components/TextField'
import TopNav from '../../components/TopNav'
import { apiFetch } from '../../lib/apiFetch'

type BoothInfo = { bankAccount: string; depositorName?: string | null }

export default function AccountPage() {
  const [bankName, setBankName] = useState('')
  const [accountNumber, setAccountNumber] = useState('')
  const [depositorName, setDepositorName] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  // O16 부스 정보 조회 — 기존 계좌를 은행명/계좌번호로 나눠 채운다 (서버는 하나의 문자열로만 저장)
  useEffect(() => {
    apiFetch('/api/v1/admin/booth')
      .then((res) => {
        if (!res.ok) throw new Error(`계좌 정보를 불러오지 못했어요 (${res.status})`)
        return res.json()
      })
      .then((data: BoothInfo) => {
        const [name, ...rest] = (data.bankAccount ?? '').trim().split(' ')
        setBankName(name ?? '')
        setAccountNumber(rest.join(' '))
        setDepositorName(data.depositorName ?? '')
      })
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false))
  }, [])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (saving) return
    setError(null)
    setSaved(false)
    setSaving(true)

    try {
      // O17 부스 설정 변경 — bankAccount·depositorName은 ADMIN 전용 필드
      const res = await apiFetch('/api/v1/admin/booth', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          bankAccount: `${bankName} ${accountNumber}`.trim(),
          depositorName: depositorName.trim() || null,
        }),
      })
      if (!res.ok) {
        if (res.status === 403) throw new Error('계좌 변경은 관리자만 할 수 있어요.')
        throw new Error(`저장에 실패했어요 (${res.status})`)
      }
      setSaved(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : '저장에 실패했어요.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />
      <SectionHeader title="계좌 등록" />

      <form onSubmit={handleSubmit} className="mx-auto flex w-full max-w-[600px] flex-col gap-6 px-6 py-10">
        <TextField
          label="은행명"
          placeholder="은행명 입력"
          value={bankName}
          onChange={(e) => setBankName(e.target.value)}
          disabled={loading}
        />
        <TextField
          label="계좌번호"
          placeholder="'-'를 제외하고 계좌번호 입력"
          inputMode="numeric"
          value={accountNumber}
          onChange={(e) => setAccountNumber(e.target.value.replace(/-/g, ''))}
          disabled={loading}
        />
        <TextField
          label="예금주명"
          placeholder="예금주명 입력"
          value={depositorName}
          onChange={(e) => setDepositorName(e.target.value)}
          disabled={loading}
        />
        {error && <p className="text-sm text-red-600">{error}</p>}
        {saved && !error && <p className="text-sm text-neutral-500">저장됐어요.</p>}
        <PrimaryButton type="submit" disabled={loading || saving} className="mt-2 disabled:opacity-40">
          {saving ? '저장 중...' : '저장하기'}
        </PrimaryButton>
      </form>
    </div>
  )
}
