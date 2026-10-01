import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import PrimaryButton from '../../components/PrimaryButton'
import SectionHeader from '../../components/SectionHeader'
import TopNav from '../../components/TopNav'
import { assetUrl } from '../../lib/apiBase'
import { apiFetch } from '../../lib/apiFetch'
import type { MenuItem } from '../../types/menu'
import BoothAmountSetting from './BoothAmountSetting'

export default function MenuListPage() {
  const [menus, setMenus] = useState<MenuItem[]>([])
  const [error, setError] = useState<string | null>(null)
  const navigate = useNavigate()
  // ?type=etc — 설정 "기타 항목 관리": 운영자 전용 항목(ETC)만 보여 준다. 일반 메뉴 목록에는 ETC를 섞지 않는다
  const [searchParams] = useSearchParams()
  const etcMode = searchParams.get('type') === 'etc'
  const shownMenus = menus.filter((m) => (m.category === 'ETC') === etcMode)

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
      <SectionHeader title={etcMode ? '자릿세 · 기타 항목 관리' : '메뉴 등록 / 편집'} />

      <div className="mx-auto flex w-full max-w-[600px] flex-col gap-4 px-6 py-10">
        {error && <p className="text-sm text-red-600">{error}</p>}
        {etcMode && (
          <BoothAmountSetting
            field="seatFeePerPerson"
            title="자릿세"
            caption="자동 청구 · 1인당"
            max={100_000}
            description="손님이 인원을 고르고 첫 주문을 넣을 때 테이블 주문내역에 따로 함께 올라가요. 0원이면 받지 않아요. 인원이 틀리면 테이블 결제 화면에서 자릿세 줄을 +/-로 고치고, 취소하면 면제돼요."
          />
        )}
        {etcMode && (
          <BoothAmountSetting
            field="minOrderAmount"
            title="최소주문금액"
            caption="테이블 첫 주문 · 자릿세 제외"
            max={1_000_000}
            description="테이블의 첫 주문은 메뉴 합계가 이 금액 이상이어야 해요. 추가 주문은 금액 제한이 없어요. 0원이면 제한 없어요."
          />
        )}
        {etcMode && (
          <p className="mt-4 text-sm text-neutral-400">
            테이블 결제 화면의 기타 탭에서 눌러 추가하는 항목이에요(손님 메뉴판에는 안 보여요). 할인·쿠폰은 금액 앞에 -를
            붙이세요. 할인은 그 테이블의 미결제 금액까지만 들어가요.
          </p>
        )}

        {shownMenus.map((menu) => (
          <Link
            key={menu.id}
            to={`/settings/menu/${menu.id}`}
            className="flex h-[120px] items-center gap-4 rounded-2xl border border-neutral-200 bg-neutral-50 px-5"
          >
            <div className="h-20 w-20 shrink-0 overflow-hidden rounded-2xl bg-neutral-300">
              {menu.imageUrl && <img src={assetUrl(menu.imageUrl)} alt="" loading="lazy" decoding="async" className="h-full w-full object-cover" />}
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

        <PrimaryButton type="button" className="mt-2" onClick={() => navigate(etcMode ? '/settings/menu/new?category=ETC' : '/settings/menu/new')}>
          신규 등록
        </PrimaryButton>
      </div>
    </div>
  )
}
