import { createContext, useContext, useState, type ReactNode } from 'react'
import type { MenuItem } from '../types/menu'

/**
 * 백엔드 메뉴 API(O7/O8) 연동 전까지 쓰는 로컬 상태.
 * TODO: 연동 시 이 Context를 걷어내고 fetch(GET /menus, POST /admin/menus, PATCH /admin/menus/{id})로 교체.
 */
type MenuContextValue = {
  menus: MenuItem[]
  addMenu: (menu: Omit<MenuItem, 'id'>) => void
  updateMenu: (id: number, menu: Omit<MenuItem, 'id'>) => void
}

const MenuContext = createContext<MenuContextValue | null>(null)

const initialMenus: MenuItem[] = [
  { id: 1, name: '묵은지 김치찜', price: 12000, soldOut: false },
  { id: 2, name: '묵은지 김치찜', price: 12000, soldOut: false },
  { id: 3, name: '묵은지 김치찜', price: 12000, soldOut: false },
  { id: 4, name: '묵은지 김치찜', price: 12000, soldOut: false },
]

export function MenuProvider({ children }: { children: ReactNode }) {
  const [menus, setMenus] = useState<MenuItem[]>(initialMenus)

  const addMenu: MenuContextValue['addMenu'] = (menu) => {
    setMenus((prev) => [...prev, { ...menu, id: Math.max(0, ...prev.map((m) => m.id)) + 1 }])
  }

  const updateMenu: MenuContextValue['updateMenu'] = (id, menu) => {
    setMenus((prev) => prev.map((m) => (m.id === id ? { ...menu, id } : m)))
  }

  return <MenuContext.Provider value={{ menus, addMenu, updateMenu }}>{children}</MenuContext.Provider>
}

export function useMenus() {
  const ctx = useContext(MenuContext)
  if (!ctx) throw new Error('useMenus는 MenuProvider 안에서만 쓸 수 있어요')
  return ctx
}
