import { describe, expect, it } from 'vitest'
import { sortMenusForBoard } from './menuOrder'

type M = { name: string; price: number; category: 'MAIN' | 'SIDE' | 'DRINK' | null }
const menus: M[] = [
  { name: '콜라', price: 2000, category: 'DRINK' },
  { name: '파전', price: 15000, category: 'MAIN' },
  { name: '감자튀김', price: 5000, category: 'SIDE' },
  { name: '김치전', price: 12000, category: 'MAIN' },
  { name: '분류없음', price: 1000, category: null },
  { name: '떡볶이', price: 12000, category: 'MAIN' },
  { name: '사이다', price: 2000, category: 'DRINK' },
]
const names = (list: M[]) => list.map((m) => m.name)

describe('sortMenusForBoard — 손님 메뉴판 순서', () => {
  it('전체: 메인 → 사이드 → 음료 → 분류 없음, 분류 안에서는 가격 낮은 순(같은 가격은 등록 순)', () => {
    expect(names(sortMenusForBoard(menus))).toEqual(['김치전', '떡볶이', '파전', '감자튀김', '콜라', '사이다', '분류없음'])
  })

  it('분류 탭: 그 분류만 가격 낮은 순', () => {
    expect(names(sortMenusForBoard(menus, 'MAIN'))).toEqual(['김치전', '떡볶이', '파전'])
    expect(names(sortMenusForBoard(menus, 'DRINK'))).toEqual(['콜라', '사이다'])
  })

  it('원본 배열은 바꾸지 않는다(서버 응답 상태를 그대로 둔다)', () => {
    const before = names(menus)
    sortMenusForBoard(menus)
    expect(names(menus)).toEqual(before)
  })
})
