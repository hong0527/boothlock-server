import { Link, useNavigate } from 'react-router-dom'
import chevronRight from '../assets/icons/chevron-right.svg'
import TopNav from '../components/TopNav'
import { clearAuth, getStaff } from '../lib/auth'

const BASE_MENU_ITEMS = [
  { label: '점포명 변경', to: '/settings/booth' },
  { label: '테이블 QR 코드 생성', to: '/settings/table-qr' },
  { label: '메뉴 등록 / 편집', to: '/settings/menu' },
  { label: '계좌 등록', to: '/settings/account' },
]

const LOGOUT_ITEM = { label: '로그아웃', to: null }

export default function SettingsPage() {
  const navigate = useNavigate()
  // O19 정산 엑셀은 ADMIN 전용(백엔드 403) — STAFF에게는 눌러도 안 되는 항목을 아예 안 보여준다.
  // Figma 디자인은 없는 화면(파일럿 스코프 밖) — SettlementPage.tsx 상단 주석 참고
  const isAdmin = getStaff()?.role === 'ADMIN'
  const menuItems = [
    ...BASE_MENU_ITEMS,
    ...(isAdmin ? [{ label: '정산 엑셀 다운로드', to: '/settings/settlement' }] : []),
    LOGOUT_ITEM,
  ]

  const handleLogout = () => {
    clearAuth()
    navigate('/')
  }

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />

      <div className="mx-auto mt-10 flex w-full max-w-[600px] flex-col px-6">
        {menuItems.map(({ label, to }) => {
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
            <button key={label} type="button" onClick={handleLogout} className={rowClassName}>
              {content}
            </button>
          )
        })}
      </div>
    </div>
  )
}
