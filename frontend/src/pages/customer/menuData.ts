import type { MenuCategory } from '../../components/customer/CategoryTabs'

export type CustomerMenuItem = {
  id: number
  category: Exclude<MenuCategory, 'ALL'>
  name: string
  description: string
  price: number
  soldOut: boolean
  imageUrl?: string
}

// Backend menu API is not connected yet; keep this fixture for customer UI development.
export const customerMenuItems: CustomerMenuItem[] = [
  {
    id: 1,
    category: 'MAIN',
    name: '차돌마라떡볶이',
    description: '기름진 차돌과 매콤한 마라의 조합',
    price: 19000,
    soldOut: false,
  },
  {
    id: 2,
    category: 'MAIN',
    name: '어묵우동',
    description: '오동통한 어묵에 든든한 우동까지',
    price: 16000,
    soldOut: false,
  },
  {
    id: 3,
    category: 'SIDE',
    name: '불닭냉면',
    description: '속까지 시원해지는 불닭냉면',
    price: 8000,
    soldOut: false,
  },
  {
    id: 4,
    category: 'SIDE',
    name: '짬뽕탕',
    description: '바로 해장되는 얼큰한 짬뽕탕',
    price: 15000,
    soldOut: false,
  },
  {
    id: 5,
    category: 'SIDE',
    name: '주먹밥',
    description: '차돌마라떡볶이와 꿀조합',
    price: 4000,
    soldOut: false,
  },
  { id: 6, category: 'DRINK', name: '사이다', description: '', price: 2000, soldOut: false },
  { id: 7, category: 'DRINK', name: '콜라', description: '', price: 2000, soldOut: false },
]
