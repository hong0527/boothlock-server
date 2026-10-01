import type { CustomerMenuItem } from '../types/customer'

// '전체' 탭에서 메인메뉴 → 사이드 → 음료 순으로 보여준다. 분류 없는 메뉴는 맨 뒤
const CATEGORY_ORDER: Record<string, number> = { MAIN: 0, SIDE: 1, DRINK: 2 }

/**
 * 손님 메뉴판 표시 순서 — 분류(메인 → 사이드 → 음료 → 분류 없음) 안에서 가격 높은 순. 가격이 같으면 서버가 준 순서(등록 순)를 지킨다
 * (Array.sort는 안정 정렬). 2026-10-01 운영 요청: 등록 순으로는 운영자가 순서를 바꿀 방법이 없어 비싼 메뉴부터 보이게 가격순으로 둔다.
 * category를 주면 그 분류만 같은 기준으로 고른다
 */
export function sortMenusForBoard<T extends Pick<CustomerMenuItem, 'category' | 'price'>>(menus: readonly T[], category?: string): T[] {
  const picked = category ? menus.filter((menu) => menu.category === category) : [...menus]
  return picked.sort(
    (a, b) =>
      (CATEGORY_ORDER[a.category ?? ''] ?? 99) - (CATEGORY_ORDER[b.category ?? ''] ?? 99) || b.price - a.price,
  )
}
