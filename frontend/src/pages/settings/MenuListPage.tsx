import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import PrimaryButton from '../../components/PrimaryButton'
import SectionHeader from '../../components/SectionHeader'
import TopNav from '../../components/TopNav'
import { apiFetch } from '../../lib/apiFetch'
import type { MenuItem } from '../../types/menu'

export default function MenuListPage() {
  const [menus, setMenus] = useState<MenuItem[]>([])
  const [error, setError] = useState<string | null>(null)
  const navigate = useNavigate()

  useEffect(() => {
    apiFetch('/api/v1/admin/menus')
      .then((res) => {
        if (!res.ok) throw new Error(`메뉴 목록을 불러오지 못했어요 (${res.status})`)
        return res.json()
      })
      .then((data: { menus: MenuItem[] }) => setMenus(data.menus))
      .catch((err) => setError(err.message))
  }, [])

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />
      <SectionHeader title="메뉴 등록 / 편집" />

      <div className="mx-auto flex w-full max-w-[600px] flex-col gap-4 px-6 py-10">
        {error && <p className="text-sm text-red-600">{error}</p>}

        {menus.map((menu) => (
          <Link
            key={menu.id}
            to={`/settings/menu/${menu.id}`}
            className="flex h-[120px] items-center gap-4 rounded-2xl border border-neutral-200 bg-neutral-50 px-5"
          >
            <div className="h-20 w-20 shrink-0 overflow-hidden rounded-2xl bg-neutral-300">
              {menu.imageUrl && <img src={menu.imageUrl} alt="" className="h-full w-full object-cover" />}
            </div>
            <div className="flex flex-col gap-2">
              <span className="text-[22px] leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
                {menu.name}
              </span>
              <span className="text-[22px] leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
                {menu.price.toLocaleString()} 원
              </span>
            </div>
          </Link>
        ))}

        <PrimaryButton type="button" className="mt-2" onClick={() => navigate('/settings/menu/new')}>
          신규 등록
        </PrimaryButton>
      </div>
    </div>
  )
}
