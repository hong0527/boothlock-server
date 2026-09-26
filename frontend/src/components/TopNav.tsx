import { NavLink } from 'react-router-dom'
import { useNow } from '../lib/useNow'

const NAV_ITEMS = [
  { to: '/orders', label: '주문 현황' },
  { to: '/tables', label: '테이블' },
  { to: '/settings', label: '설정' },
]

const WEEKDAY_LABEL = ['일', '월', '화', '수', '목', '금', '토']

/** 상단 상시 시계 — 1초 간격으로만 이 부분을 다시 그린다(TopNav 전체를 초 단위로 리렌더하지 않게 분리) */
function LiveClock() {
  const now = useNow(1000)
  const time = new Date(now)
  const hh = String(time.getHours()).padStart(2, '0')
  const mm = String(time.getMinutes()).padStart(2, '0')
  const ss = String(time.getSeconds()).padStart(2, '0')
  const weekday = WEEKDAY_LABEL[time.getDay()]
  return (
    <span className="absolute right-8 text-3xl leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
      ({weekday}) {hh}:{mm}:{ss}
    </span>
  )
}

export default function TopNav() {
  return (
    <nav className="relative flex items-center justify-center gap-24 bg-neutral-50 py-5">
      {NAV_ITEMS.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          className={({ isActive }) =>
            `text-2xl leading-[1.2] font-bold tracking-[-0.04em] ${
              isActive ? 'text-neutral-900' : 'text-neutral-400'
            }`
          }
        >
          {item.label}
        </NavLink>
      ))}
      <LiveClock />
    </nav>
  )
}
