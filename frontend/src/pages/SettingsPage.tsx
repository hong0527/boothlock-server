import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import chevronRight from '../assets/icons/chevron-right.svg'
import AlertSettingsPanel from '../components/AlertSettingsPanel'
import TopNav from '../components/TopNav'
import { clearAuth, getStaff } from '../lib/auth'

const BASE_MENU_ITEMS = [
  { label: '점포명 변경', to: '/settings/booth' },
  { label: '테이블 QR 코드 생성', to: '/settings/table-qr' },
  { label: '메뉴 등록 / 편집', to: '/settings/menu' },
  // 자릿세(자동 청구, ADMIN만 변경)와 기타 항목(추가 자릿세·쿠폰 등 — 결제 모달 기타 탭, 메뉴와 같은 권한)을 한 화면에서 관리
  { label: '자릿세 · 기타 항목 관리', to: '/settings/menu?type=etc' },
  { label: '계좌 등록', to: '/settings/account' },
]

// 알림 설정은 하위 화면으로 넘어가지 않고 이 목록 안에서 펼친다 — 다른 항목과 같은 줄로 두고, 누르면 바로 아래에 열린다
const ALERT_ITEM = { label: '알림 설정', to: null, kind: 'alert' as const }
const LOGOUT_ITEM = { label: '로그아웃', to: null, kind: 'logout' as const }

const ROW_CLASS =
  'flex h-[60px] w-full items-center justify-between border-b border-neutral-100 text-left text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900'

export default function SettingsPage() {
  const navigate = useNavigate()
  // O19 정산 엑셀은 ADMIN 전용(백엔드 403) — STAFF에게는 눌러도 안 되는 항목을 아예 안 보여준다.
  // Figma 디자인은 없는 화면(파일럿 스코프 밖) — SettlementPage.tsx 상단 주석 참고
  const isAdmin = getStaff()?.role === 'ADMIN'
  const menuItems = [
    ...BASE_MENU_ITEMS,
    ...(isAdmin
      ? [
          { label: '오늘 판매 현황', to: '/settings/sales' },
          { label: '정산 엑셀 다운로드', to: '/settings/settlement' },
        ]
      : []),
    ALERT_ITEM,
    LOGOUT_ITEM,
  ]
  const [alertOpen, setAlertOpen] = useState(false)

  const handleLogout = () => {
    clearAuth()
    navigate('/')
  }

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />

      <div className="mx-auto mt-10 flex w-full max-w-[600px] flex-col px-6">
        {menuItems.map((item) => {
          if (item.to) {
            return (
              <Link key={item.label} to={item.to} className={ROW_CLASS}>
                {item.label}
                <img src={chevronRight} alt="" className="h-6 w-6" />
              </Link>
            )
          }
          if ('kind' in item && item.kind === 'alert') {
            return (
              <div key={item.label}>
                <button
                  type="button"
                  onClick={() => setAlertOpen((open) => !open)}
                  aria-expanded={alertOpen}
                  aria-controls="alert-settings-panel"
                  className={ROW_CLASS}
                >
                  {item.label}
                  {/* 펼침 표시 — 다른 항목의 ">"를 아래로 돌려 "여기서 열린다"를 보여 준다 */}
                  <img
                    src={chevronRight}
                    alt=""
                    className={`h-6 w-6 transition-transform ${alertOpen ? '-rotate-90' : 'rotate-90'}`}
                  />
                </button>
                {alertOpen && <AlertSettingsPanel id="alert-settings-panel" />}
              </div>
            )
          }
          return (
            <button key={item.label} type="button" onClick={handleLogout} className={ROW_CLASS}>
              {item.label}
              <img src={chevronRight} alt="" className="h-6 w-6" />
            </button>
          )
        })}
      </div>
    </div>
  )
}
