import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import cameraIcon from '../../assets/icons/camera.svg'
import PrimaryButton from '../../components/PrimaryButton'
import SectionHeader from '../../components/SectionHeader'
import TextField from '../../components/TextField'
import TopNav from '../../components/TopNav'
import { apiFetch } from '../../lib/apiFetch'
import type { MenuItem } from '../../types/menu'

export default function MenuEditPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const editingId = id ? Number(id) : undefined

  const [name, setName] = useState('')
  const [price, setPrice] = useState('')
  const [soldOut, setSoldOut] = useState(false)
  const [imageUrl, setImageUrl] = useState<string | undefined>(undefined)
  const [previewUrl, setPreviewUrl] = useState<string | undefined>(undefined)
  const [uploading, setUploading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 등록 도중 저장(POST) 성공 + 품절 반영(PATCH) 실패 시, 재시도가 같은 이름으로 또 POST하지 않도록
  const [createdId, setCreatedId] = useState<number | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const previewUrlRef = useRef(previewUrl)
  previewUrlRef.current = previewUrl
  const uploadSeqRef = useRef(0)

  // 편집 모드면 목록 API에서 해당 메뉴를 찾아 폼을 채운다 (메뉴 단건 조회 API가 없어 목록에서 찾음)
  useEffect(() => {
    if (!editingId) return
    setError(null)
    setPreviewUrl((prev) => {
      if (prev) URL.revokeObjectURL(prev)
      return undefined
    })
    setCreatedId(null)
    apiFetch('/api/v1/admin/menus')
      .then((res) => {
        if (!res.ok) throw new Error(`메뉴 정보를 불러오지 못했어요 (${res.status})`)
        return res.json()
      })
      .then((data: { menus: MenuItem[] }) => {
        const menu = data.menus.find((m) => m.id === editingId)
        if (!menu) {
          setError('메뉴를 찾을 수 없어요.')
          return
        }
        setName(menu.name)
        setPrice(menu.price.toString())
        setSoldOut(menu.soldOut)
        setImageUrl(menu.imageUrl)
      })
      .catch((err) => setError(err.message))
  }, [editingId])

  // 마지막으로 고른 사진의 blob 미리보기를 페이지 떠날 때 해제
  useEffect(() => {
    return () => {
      if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current)
    }
  }, [])

  const handleImagePick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    // 업로드 도중 사진을 다시 고르면 이전 요청의 응답이 나중에 와서 결과를 덮어쓰지 않도록 순번으로 최신 요청만 반영한다
    const seq = ++uploadSeqRef.current

    setPreviewUrl((prev) => {
      if (prev) URL.revokeObjectURL(prev)
      return URL.createObjectURL(file)
    })
    setError(null)
    setUploading(true)

    const formData = new FormData()
    formData.append('file', file)
    apiFetch('/api/v1/admin/uploads', { method: 'POST', body: formData })
      .then((res) => {
        if (!res.ok) throw new Error(`사진 업로드에 실패했어요 (${res.status})`)
        return res.json()
      })
      .then((data: { url: string }) => {
        if (seq === uploadSeqRef.current) setImageUrl(data.url)
      })
      .catch((err) => {
        if (seq === uploadSeqRef.current) setError(err.message)
      })
      .finally(() => {
        if (seq === uploadSeqRef.current) setUploading(false)
      })
  }

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault()
    if (saving || uploading) return
    setError(null)
    setSaving(true)

    try {
      // 등록 중 품절 반영(PATCH)만 실패했다면 createdId가 이미 채워져 있어, 재시도는 같은 이름으로 또 등록하지 않고 이어서 수정한다
      const targetId = editingId ?? createdId
      if (targetId) {
        const res = await apiFetch(`/api/v1/admin/menus/${targetId}`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name, price: Number(price) || 0, soldOut, imageUrl: imageUrl ?? null }),
        })
        if (!res.ok) throw new Error(`저장에 실패했어요 (${res.status})`)
      } else {
        const res = await apiFetch('/api/v1/admin/menus', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name, price: Number(price) || 0, imageUrl }),
        })
        if (!res.ok) throw new Error(`등록에 실패했어요 (${res.status})`)
        const created: MenuItem = await res.json()
        setCreatedId(created.id)
        // O7은 항상 품절 아님으로 생성돼서, 등록과 동시에 품절로 표시했다면 이어서 반영한다
        if (soldOut) {
          const patchRes = await apiFetch(`/api/v1/admin/menus/${created.id}`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ soldOut: true }),
          })
          if (!patchRes.ok) throw new Error(`품절 반영에 실패했어요 (${patchRes.status})`)
        }
      }
      navigate('/settings/menu')
    } catch (err) {
      setError(err instanceof Error ? err.message : '저장에 실패했어요.')
    } finally {
      setSaving(false)
    }
  }

  const displayImage = previewUrl ?? imageUrl

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />
      <SectionHeader title="메뉴 등록 / 편집" />

      <form onSubmit={handleSave} className="mx-auto flex w-full max-w-[600px] flex-col items-center gap-6 px-6 py-10">
        <button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          className="flex h-[120px] w-[120px] items-center justify-center overflow-hidden rounded-xl bg-neutral-300"
        >
          {displayImage ? (
            <img src={displayImage} alt="" className="h-full w-full object-cover" />
          ) : (
            <img src={cameraIcon} alt="사진 선택" className="h-10 w-10" />
          )}
        </button>
        <input ref={fileInputRef} type="file" accept="image/*" onChange={handleImagePick} className="hidden" />

        <div className="flex w-full flex-col gap-6">
          <TextField label="메뉴명" placeholder="메뉴명 입력" value={name} onChange={(e) => setName(e.target.value)} />
          <TextField
            label="가격"
            placeholder="가격 입력"
            inputMode="numeric"
            value={price}
            onChange={(e) => setPrice(e.target.value.replace(/[^0-9]/g, ''))}
          />

          <div>
            <span className="block text-sm leading-[1.5] tracking-[-0.04em] text-neutral-400">품절 여부</span>
            <div className="mt-2 flex gap-3">
              <button
                type="button"
                onClick={() => setSoldOut(true)}
                className={`h-[60px] flex-1 rounded-xl border text-base leading-[1.5] tracking-[-0.04em] ${
                  soldOut
                    ? 'border-neutral-600 bg-neutral-600 text-neutral-50'
                    : 'border-neutral-100 bg-neutral-50 text-neutral-400'
                }`}
              >
                품절 O
              </button>
              <button
                type="button"
                onClick={() => setSoldOut(false)}
                className={`h-[60px] flex-1 rounded-xl border text-base leading-[1.5] tracking-[-0.04em] ${
                  !soldOut
                    ? 'border-neutral-600 bg-neutral-600 text-neutral-50'
                    : 'border-neutral-100 bg-neutral-50 text-neutral-400'
                }`}
              >
                품절 X
              </button>
            </div>
          </div>
        </div>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <PrimaryButton type="submit" disabled={saving || uploading} className="mt-2 disabled:opacity-40">
          {uploading ? '사진 업로드 중...' : saving ? '저장 중...' : '저장하기'}
        </PrimaryButton>
      </form>
    </div>
  )
}
