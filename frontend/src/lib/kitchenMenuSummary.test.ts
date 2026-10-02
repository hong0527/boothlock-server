import { describe, expect, it } from 'vitest'
import type { OrderItemSummary, OrderStatus, OrderSummary } from '../types/dashboard'
import { kitchenMenuSummary } from './kitchenMenuSummary'

function item(menuName: string, qty: number, itemType: OrderItemSummary['itemType'] = 'MENU'): OrderItemSummary {
  const menuId = menuName === '떡볶이' ? 2 : menuName === '콜라' ? 3 : 1
  return { itemId: 1, menuId: itemType === 'MENU' ? menuId : null, menuName, qty, unitPrice: 1000, itemType }
}

function order(items: OrderItemSummary[], status: OrderStatus = 'RECEIVED'): OrderSummary {
  return {
    orderId: 1, orderNo: 'A-1', status, items, paymentStatus: 'UNPAID', paymentMethod: null,
    totalAmount: 1000, createdAt: '2026-10-02T18:00:00+09:00', tableLabel: 'A-1', manual: false, sessionId: 1,
  }
}

describe('주방 메뉴 총 수량', () => {
  it('여러 주문의 같은 메뉴를 합산하고 수량 많은 순으로 보여준다', () => {
    expect(kitchenMenuSummary([
      order([item('후라이드 치킨', 5), item('떡볶이', 8), item('콜라', 7)]),
      order([item('후라이드 치킨', 7), item('콜라', 10)]),
    ])).toEqual([{ menuId: 3, name: '콜라', qty: 17 }, { menuId: 1, name: '후라이드 치킨', qty: 12 }, { menuId: 2, name: '떡볶이', qty: 8 }])
  })

  it('승인대기·완료·취소/거절 주문을 제외한다', () => {
    expect(kitchenMenuSummary([
      order([item('치킨', 2)]),
      order([item('치킨', 10)], 'PENDING_APPROVAL'),
      order([item('치킨', 20)], 'DONE'),
      order([item('치킨', 30)], 'CANCELED'),
    ])).toEqual([{ menuId: 1, name: '치킨', qty: 2 }])
  })

  it('자릿세·할인·기타 항목은 제외한다', () => {
    expect(kitchenMenuSummary([order([
      item('치킨', 2), item('자릿세', 4, 'SEAT_FEE'), item('할인', 1, 'EXTRA'), item('기타', 3, 'EXTRA'),
    ])])).toEqual([{ menuId: 1, name: '치킨', qty: 2 }])
  })

  it('결제 여부와 수기 주문 여부는 조리 대상 수량에 영향을 주지 않는다', () => {
    expect(kitchenMenuSummary([
      order([item('치킨', 2)]),
      { ...order([item('치킨', 3)]), paymentStatus: 'PAID', manual: true, sessionId: null },
    ])).toEqual([{ menuId: 1, name: '치킨', qty: 5 }])
  })

  it('동일 수량은 메뉴명 순, 빈 주문이나 메뉴가 없는 주문은 빈 집계', () => {
    expect(kitchenMenuSummary([order([item('치킨', 1), item('떡볶이', 1)])])).toEqual([
      { menuId: 2, name: '떡볶이', qty: 1 }, { menuId: 1, name: '치킨', qty: 1 },
    ])
    expect(kitchenMenuSummary([])).toEqual([])
    expect(kitchenMenuSummary([order([item('자릿세', 4, 'SEAT_FEE')])])).toEqual([])
  })

  it('orders/items의 null·undefined 및 비정상 배열 원소를 안전하게 제외한다', () => {
    for (const value of [null, undefined, {}, 1]) expect(kitchenMenuSummary(value)).toEqual([])
    expect(kitchenMenuSummary([
      null, undefined, { status: 'RECEIVED', items: null }, { status: 'RECEIVED', items: undefined },
      { status: 'RECEIVED', items: [null, undefined, item('치킨', 2)] },
    ])).toEqual([{ menuId: 1, name: '치킨', qty: 2 }])
  })

  it('menuName/qty의 null·undefined, 빈 이름과 비정상 수량·ID는 제외한다', () => {
    const valid = item('치킨', 2)
    expect(kitchenMenuSummary([{ status: 'RECEIVED', items: [
      valid,
      ...[null, undefined, '', 1].map(menuName => ({ ...valid, menuName })),
      ...[null, undefined, NaN, Infinity, '2', -1, 0, 1.5].map(qty => ({ ...valid, qty })),
      ...[null, undefined, -1, '1'].map(menuId => ({ ...valid, menuId })),
    ] }])).toEqual([{ menuId: 1, name: '치킨', qty: 2 }])
  })

  it('동일 menuId는 이름 변경 전후 수량을 합산하고 최신 주문 이름을 표시한다', () => {
    const older = { ...order([item('옛 이름', 2)]), orderId: 1, createdAt: '2026-10-02T18:00:00+09:00' }
    const newer = { ...order([item('새 이름', 3)]), orderId: 2, createdAt: '2026-10-02T18:10:00+09:00' }
    for (const orders of [[older, newer], [newer, older]]) {
      expect(kitchenMenuSummary(orders)).toEqual([{ menuId: 1, name: '새 이름', qty: 5 }])
    }
  })

  it('이름이 같아도 다른 menuId는 합치지 않는다', () => {
    expect(kitchenMenuSummary([order([
      item('치킨', 2), { ...item('치킨', 3), menuId: 2 },
    ])])).toEqual([{ menuId: 2, name: '치킨', qty: 3 }, { menuId: 1, name: '치킨', qty: 2 }])
  })

  it('미체크 2개 → 1개 나감 → 전부 나감 → 해제를 반영하고 다른 메뉴에는 영향이 없다', () => {
    const orders = [order([
      { ...item('치킨', 1), itemId: 1 },
      { ...item('치킨', 1), itemId: 2 },
      { ...item('콜라', 3), itemId: 3 },
    ])]
    const cola = { menuId: 3, name: '콜라', qty: 3 }
    const summarize = (ids: number[]) => kitchenMenuSummary(orders, new Map([[1, new Set(ids)]]))
    expect(summarize([])).toEqual([cola, { menuId: 1, name: '치킨', qty: 2 }])
    expect(summarize([1])).toEqual([cola, { menuId: 1, name: '치킨', qty: 1 }])
    expect(summarize([1, 2])).toEqual([cola])
    expect(summarize([2])).toEqual([cola, { menuId: 1, name: '치킨', qty: 1 }])
    expect(summarize([])).toEqual([cola, { menuId: 1, name: '치킨', qty: 2 }])
  })

  it('수량이 여러 개인 item은 체크 시 그 item 수량 전체를 제외하며 주문별 체크를 구분한다', () => {
    const first = order([{ ...item('치킨', 2), itemId: 1 }])
    const second = { ...order([{ ...item('치킨', 3), itemId: 2 }]), orderId: 2 }
    expect(kitchenMenuSummary([first, second], new Map([[1, new Set([1])]])))
      .toEqual([{ menuId: 1, name: '치킨', qty: 3 }])
    expect(kitchenMenuSummary([first], new Map([[1, new Set([1])]]))).toEqual([])
  })
})
