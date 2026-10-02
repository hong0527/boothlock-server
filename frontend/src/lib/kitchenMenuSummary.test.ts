import { describe, expect, it } from 'vitest'
import type { OrderItemSummary, OrderStatus, OrderSummary } from '../types/dashboard'
import { kitchenMenuSummary } from './kitchenMenuSummary'

function item(menuName: string, qty: number, itemType: OrderItemSummary['itemType'] = 'MENU'): OrderItemSummary {
  return { itemId: 1, menuId: itemType === 'MENU' ? 1 : null, menuName, qty, unitPrice: 1000, itemType }
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
    ])).toEqual([{ name: '콜라', qty: 17 }, { name: '후라이드 치킨', qty: 12 }, { name: '떡볶이', qty: 8 }])
  })

  it('승인대기·완료·취소/거절 주문을 제외한다', () => {
    expect(kitchenMenuSummary([
      order([item('치킨', 2)]),
      order([item('치킨', 10)], 'PENDING_APPROVAL'),
      order([item('치킨', 20)], 'DONE'),
      order([item('치킨', 30)], 'CANCELED'),
    ])).toEqual([{ name: '치킨', qty: 2 }])
  })

  it('자릿세·할인·기타 항목은 제외한다', () => {
    expect(kitchenMenuSummary([order([
      item('치킨', 2), item('자릿세', 4, 'SEAT_FEE'), item('할인', 1, 'EXTRA'), item('기타', 3, 'EXTRA'),
    ])])).toEqual([{ name: '치킨', qty: 2 }])
  })

  it('결제 여부와 수기 주문 여부는 조리 대상 수량에 영향을 주지 않는다', () => {
    expect(kitchenMenuSummary([
      order([item('치킨', 2)]),
      { ...order([item('치킨', 3)]), paymentStatus: 'PAID', manual: true, sessionId: null },
    ])).toEqual([{ name: '치킨', qty: 5 }])
  })

  it('동일 수량은 메뉴명 순, 빈 주문이나 메뉴가 없는 주문은 빈 집계', () => {
    expect(kitchenMenuSummary([order([item('치킨', 1), item('떡볶이', 1)])])).toEqual([
      { name: '떡볶이', qty: 1 }, { name: '치킨', qty: 1 },
    ])
    expect(kitchenMenuSummary([])).toEqual([])
    expect(kitchenMenuSummary([order([item('자릿세', 4, 'SEAT_FEE')])])).toEqual([])
  })
})
