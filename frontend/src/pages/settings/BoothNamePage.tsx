import { useEffect, useState } from 'react'
import PrimaryButton from '../../components/PrimaryButton'
import SectionHeader from '../../components/SectionHeader'
import TextField from '../../components/TextField'
import TopNav from '../../components/TopNav'
import { updateStoredBoothName } from '../../lib/auth'
import { apiFetch } from '../../lib/apiFetch'

type BoothInfo = { name: string }
type ErrorBody = { error?: { message?: string } }

/** 서버가 `name`에 허용하는 길이 — 백엔드 BoothSettingsService.requiredText(..., 50)와 같은 값 */
const MAX_LENGTH = 50

/**
 * 점포명 변경 (O16 조회 + O17 변경의 `name` 필드).
 *
 * 부스명은 시더가 심은 DB 값이라 그동안 운영자가 화면에서 확인할 방법도, 고칠 방법도 없었다.
 * 백엔드는 처음부터 `name` 변경을 허용하고 있었고(ADMIN 아닌 STAFF도 가능), 빠져 있던 건 이 화면뿐이다.
 *
 * 바꾼 이름은 손님 화면(메뉴판 상단·홈 부스 목록·QR 진입)에 그대로 나간다. 손님 홈 목록만 서버
 * 캐시 때문에 최대 10초 늦게 반영된다(boothlock.event.booths-cache-seconds).
 */
export default function BoothNamePage() {
  const [name, setName] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  // O16 부스 정보 조회 — 지금 이름을 그대로 보여준다(운영자가 자기 부스명을 볼 수 있는 유일한 화면이다)
  useEffect(() => {
    apiFetch('/api/v1/admin/booth')
      .then((res) => {
        if (!res.ok) throw new Error(`부스 정보를 불러오지 못했어요 (${res.status})`)
        return res.json()
      })
      .then((data: BoothInfo) => setName(data.name ?? ''))
      .catch((err) => setError(err instanceof Error ? err.message : '부스 정보를 불러오지 못했어요.'))
      .finally(() => setLoading(false))
  }, [])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (saving) return
    setError(null)
    setSaved(false)

    const trimmed = name.trim()
    // 서버도 공백만·50자 초과를 400으로 막지만, 손님에게 그대로 보이는 상호라 여기서 먼저 걸러 안내한다
    if (!trimmed) {
      setError('점포명을 입력해주세요.')
      return
    }
    if (trimmed.length > MAX_LENGTH) {
      setError(`점포명은 ${MAX_LENGTH}자까지 입력할 수 있어요.`)
      return
    }

    setSaving(true)
    try {
      // O17 부분 변경 — name만 보낸다. 다른 필드(계좌·운영시간 등)는 건드리지 않는다.
      const res = await apiFetch('/api/v1/admin/booth', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: trimmed }),
      })
      if (!res.ok) {
        // 서버가 이유를 알려주면(400 길이·형식) 그 문장을 그대로 보여준다
        const body = (await res.json().catch(() => null)) as ErrorBody | null
        throw new Error(body?.error?.message ?? `저장에 실패했어요 (${res.status})`)
      }
      setName(trimmed)
      // 로그인 때 받은 부스명이 localStorage에 굳어 있다 — 재로그인 없이도 맞게 유지한다
      updateStoredBoothName(trimmed)
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
      <SectionHeader title="점포명 변경" />

      <form onSubmit={handleSubmit} className="mx-auto flex w-full max-w-[600px] flex-col gap-6 px-6 py-10">
        <TextField
          label="점포명"
          placeholder="점포명 입력"
          value={name}
          maxLength={MAX_LENGTH}
          onChange={(e) => {
            setName(e.target.value)
            setSaved(false)
          }}
          disabled={loading}
        />
        <p className="text-sm text-neutral-400">손님이 보는 메뉴판과 부스 목록에 이 이름이 그대로 나와요.</p>
        {error && <p className="text-sm text-red-600">{error}</p>}
        {saved && !error && <p className="text-sm text-neutral-500">저장됐어요.</p>}
        <PrimaryButton type="submit" disabled={loading || saving} className="mt-2 disabled:opacity-40">
          {saving ? '저장 중...' : '저장하기'}
        </PrimaryButton>
      </form>
    </div>
  )
}
