import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import cameraIcon from '../../assets/icons/camera.svg'
import PrimaryButton from '../../components/PrimaryButton'
import SectionHeader from '../../components/SectionHeader'
import TextField from '../../components/TextField'
import TopNav from '../../components/TopNav'
import { useMenus } from '../../context/MenuContext'

export default function MenuEditPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { menus, addMenu, updateMenu } = useMenus()
  const editing = id ? menus.find((m) => m.id === Number(id)) : undefined

  const [name, setName] = useState(editing?.name ?? '')
  const [price, setPrice] = useState(editing?.price.toString() ?? '')
  const [soldOut, setSoldOut] = useState(editing?.soldOut ?? false)
  const [imageUrl, setImageUrl] = useState(editing?.imageUrl)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const imageUrlRef = useRef(imageUrl)
  imageUrlRef.current = imageUrl

  // id가 바뀌면(언마운트 없이 /settings/menu/1 -> /settings/menu/2 같은 이동) 폼을 그 메뉴 값으로 다시 채운다
  useEffect(() => {
    setName(editing?.name ?? '')
    setPrice(editing?.price.toString() ?? '')
    setSoldOut(editing?.soldOut ?? false)
    setImageUrl(editing?.imageUrl)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  const handleImagePick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setImageUrl((prev) => {
      if (prev?.startsWith('blob:')) URL.revokeObjectURL(prev)
      return URL.createObjectURL(file)
    })
  }

  // 마지막으로 고른 사진의 blob URL을 페이지 떠날 때 해제
  useEffect(() => {
    return () => {
      if (imageUrlRef.current?.startsWith('blob:')) URL.revokeObjectURL(imageUrlRef.current)
    }
  }, [])

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault()
    const menu = { name, price: Number(price) || 0, soldOut, imageUrl }
    // TODO: API 연결 시 POST /admin/menus 또는 PATCH /admin/menus/{id}로 교체
    if (editing) {
      updateMenu(editing.id, menu)
    } else {
      addMenu(menu)
    }
    navigate('/settings/menu')
  }

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
          {imageUrl ? (
            <img src={imageUrl} alt="" className="h-full w-full object-cover" />
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

        <PrimaryButton type="submit" className="mt-2">
          저장하기
        </PrimaryButton>
      </form>
    </div>
  )
}
