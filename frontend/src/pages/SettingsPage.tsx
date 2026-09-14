import { Link } from 'react-router-dom'
import chevronRight from '../assets/icons/chevron-right.svg'
import TopNav from '../components/TopNav'

// '로그아웃'은 아직 로그인 연동 전이라 클릭해도 동작 없음 — TODO: 토큰 삭제 + '/'로 이동 연결
const MENU_ITEMS = [
  { label: '테이블 QR 코드 생성', to: '/settings/table-qr' },
  { label: '메뉴 등록 / 편집', to: '/settings/menu' },
  { label: '계좌 등록', to: '/settings/account' },
  { label: '로그아웃', to: null },
]

export default function SettingsPage() {
  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />

      <div className="mx-auto mt-10 flex w-full max-w-[600px] flex-col px-6">
        {MENU_ITEMS.map(({ label, to }) => {
          const rowClassName =
            'flex h-[60px] items-center justify-between border-b border-neutral-100 text-left text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900'
          const content = (
            <>
              {label}
              <img src={chevronRight} alt="" className="h-6 w-6" />
            </>
          )
          return to ? (
            <Link key={label} to={to} className={rowClassName}>
              {content}
            </Link>
          ) : (
            <button key={label} type="button" className={rowClassName}>
              {content}
            </button>
          )
        })}
      </div>
    </div>
  )
}
