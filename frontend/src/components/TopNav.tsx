import { NavLink } from 'react-router-dom'

const NAV_ITEMS = [
  { to: '/orders', label: '주문 현황' },
  { to: '/tables', label: '테이블' },
  { to: '/settings', label: '설정' },
]

export default function TopNav() {
  return (
    <nav className="flex items-center justify-center gap-24 bg-neutral-50 py-5">
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
    </nav>
  )
}
