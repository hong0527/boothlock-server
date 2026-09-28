import { describe, expect, it } from 'vitest'
import { additionalOrderIds, confirmPaymentMessage, formatWon, orderedForTab } from './dashboardOrders'
import type { OrderStatus, OrderSummary } from '../types/dashboard'

function item(itemType: 'MENU' | 'SEAT_FEE' | 'EXTRA') {
  return { itemId: 1, menuId: itemType === 'MENU' ? 1 : null, menuName: itemType, unitPrice: 1000, qty: 1, itemType }
}

/** 서버(O10)가 주는 순서를 흉내낸다 — 최신 주문이 먼저 */
function order(
  orderNo: string,
  createdAt: string,
  status: OrderStatus = 'RECEIVED',
  sessionId: number | null = 1,
  itemType: 'MENU' | 'SEAT_FEE' | 'EXTRA' = 'MENU',
): OrderSummary {
  return {
    orderId: Number(orderNo.replace(/\D/g, '')),
    orderNo,
    status,
    paymentStatus: 'UNPAID',
    paymentMethod: null,
    totalAmount: 1000,
    items: [item(itemType)],
    createdAt,
    tableLabel: 'A-1',
    manual: false,
    sessionId,
  }
}

// 서버 응답 순서: 최신 → 오래된 순
const newestFirst = [
  order('A-3', '2026-09-21T18:30:00'),
  order('A-2', '2026-09-21T18:20:00'),
  order('A-1', '2026-09-21T18:10:00'),
]

describe('orderedForTab', () => {
  it('진행 탭은 먼저 들어온 주문을 앞에 둔다', () => {
    expect(orderedForTab('RECEIVED', newestFirst).map((o) => o.orderNo)).toEqual(['A-1', 'A-2', 'A-3'])
  })

  it('승인대기 탭도 진행 탭과 같이 먼저 들어온 주문을 앞에 둔다 (O28)', () => {
    expect(orderedForTab('PENDING_APPROVAL', newestFirst).map((o) => o.orderNo)).toEqual(['A-1', 'A-2', 'A-3'])
  })

  it('완료 탭은 서버 순서(최신 먼저)를 그대로 쓴다', () => {
    expect(orderedForTab('DONE', newestFirst).map((o) => o.orderNo)).toEqual(['A-3', 'A-2', 'A-1'])
  })

  it('취소 탭도 서버 순서를 그대로 쓴다', () => {
    expect(orderedForTab('CANCELED', newestFirst).map((o) => o.orderNo)).toEqual(['A-3', 'A-2', 'A-1'])
  })

  it('원본 배열을 제자리에서 뒤집지 않는다', () => {
    const source = [...newestFirst]
    orderedForTab('RECEIVED', source)
    expect(source.map((o) => o.orderNo)).toEqual(['A-3', 'A-2', 'A-1'])
  })

  it('빈 목록도 그대로 빈 목록이다', () => {
    expect(orderedForTab('RECEIVED', [])).toEqual([])
    expect(orderedForTab('DONE', [])).toEqual([])
  })

  it('1건뿐이면 탭과 무관하게 같은 결과다', () => {
    const one = [order('A-1', '2026-09-21T18:10:00')]
    expect(orderedForTab('RECEIVED', one).map((o) => o.orderNo)).toEqual(['A-1'])
    expect(orderedForTab('DONE', one).map((o) => o.orderNo)).toEqual(['A-1'])
  })
})

describe('additionalOrderIds', () => {
  it('같은 세션에 주문이 하나뿐이면 추가 주문이 아니다', () => {
    const orders = [order('A-1', '2026-09-21T18:10:00', 'RECEIVED', 1)]
    expect(additionalOrderIds(orders).size).toBe(0)
  })

  it('같은 세션의 두 번째 이후 주문만 추가 주문으로 잡는다 — 응답 순서와 무관하게 생성 시각으로 판정', () => {
    const orders = [
      order('A-2', '2026-09-21T18:20:00', 'RECEIVED', 1), // 나중 주문이 응답에서 먼저 와도
      order('A-1', '2026-09-21T18:10:00', 'RECEIVED', 1), // 생성 시각 기준으로 이게 첫 주문
    ]
    const ids = additionalOrderIds(orders)
    expect(ids.has(2)).toBe(true)
    expect(ids.has(1)).toBe(false)
  })

  it('탭이 갈려 있어도(진행+완료 합친 배열) 같은 세션이면 잡는다', () => {
    const orders = [
      order('A-1', '2026-09-21T17:00:00', 'DONE', 1),
      order('A-2', '2026-09-21T18:00:00', 'RECEIVED', 1),
    ]
    expect([...additionalOrderIds(orders)]).toEqual([2])
  })

  it('서로 다른 세션이면 각자 첫 주문이라 아무도 추가 주문이 아니다', () => {
    const orders = [
      order('A-1', '2026-09-21T18:10:00', 'RECEIVED', 1),
      order('B-1', '2026-09-21T18:15:00', 'RECEIVED', 2),
    ]
    expect(additionalOrderIds(orders).size).toBe(0)
  })

  it('수기 주문(sessionId 없음)은 여러 건이어도 대상에서 빠진다', () => {
    const orders = [
      order('M-1', '2026-09-21T18:10:00', 'RECEIVED', null),
      order('M-2', '2026-09-21T18:20:00', 'RECEIVED', null),
    ]
    expect(additionalOrderIds(orders).size).toBe(0)
  })

  it('세 번째 주문도 잡는다', () => {
    const orders = [
      order('A-1', '2026-09-21T18:00:00', 'RECEIVED', 1),
      order('A-2', '2026-09-21T18:10:00', 'RECEIVED', 1),
      order('A-3', '2026-09-21T18:20:00', 'RECEIVED', 1),
    ]
    expect([...additionalOrderIds(orders)].sort()).toEqual([2, 3])
  })

  // 서버는 자릿세를 첫 메뉴 주문의 항목이 아니라 같은 createdAt을 가진 별도 주문으로 만든다
  // (OrderWriter.saveSeatFeeOrder) — 세면 자릿세 카드가 "두 번째"로 잡혀 정작 첫 주문에 배지가 붙는다
  it('세션 첫 주문과 같은 시각에 생기는 자릿세 주문은 세지 않는다', () => {
    const orders = [
      order('A-1', '2026-09-21T18:00:00', 'DONE', 1, 'SEAT_FEE'),
      order('A-2', '2026-09-21T18:00:00', 'PENDING_APPROVAL', 1),
    ]
    expect(additionalOrderIds(orders).size).toBe(0)
  })

  it('결제 모달로 넣는 기타 항목(쿠폰·추가 자릿세) 주문도 세지 않는다', () => {
    const orders = [
      order('A-1', '2026-09-21T18:00:00', 'RECEIVED', 1),
      order('A-2', '2026-09-21T18:10:00', 'DONE', 1, 'EXTRA'),
    ]
    expect(additionalOrderIds(orders).size).toBe(0)
  })

  // 자릿세 취소 = 면제 처리, 거절(O28)도 CANCELED — 그걸 첫 주문으로 세면 진짜 첫 메뉴 주문에 배지가 붙는다
  it('취소된 주문은 첫 주문으로 세지 않는다', () => {
    const orders = [
      order('A-1', '2026-09-21T18:00:00', 'CANCELED', 1),
      order('A-2', '2026-09-21T18:10:00', 'RECEIVED', 1),
    ]
    expect(additionalOrderIds(orders).size).toBe(0)
  })

  // ICU 정렬은 '.'을 '+'보다 앞에 둬서, 소수점 유무가 갈리면 localeCompare가 순서를 뒤집는다
  it('소수점 자릿수가 다른 같은 초의 두 주문도 시각 순으로 가른다', () => {
    const orders = [
      order('A-2', '2026-09-21T18:10:00.5+09:00', 'RECEIVED', 1),
      order('A-1', '2026-09-21T18:10:00+09:00', 'RECEIVED', 1),
    ]
    expect([...additionalOrderIds(orders)]).toEqual([2])
  })

  it('생성 시각이 완전히 같으면 orderId가 작은 쪽을 첫 주문으로 본다', () => {
    const orders = [
      order('A-2', '2026-09-21T18:10:00', 'RECEIVED', 1),
      order('A-1', '2026-09-21T18:10:00', 'RECEIVED', 1),
    ]
    expect([...additionalOrderIds(orders)]).toEqual([2])
  })
})

describe('결제 확인 문구·금액 표시', () => {
  it('금액은 천 단위 쉼표와 "원"', () => {
    expect(formatWon(12000)).toBe('12,000원')
  })

  it('확인창에 주문번호와 금액을 보여준다', () => {
    const message = confirmPaymentMessage({ ...order('T1-7', '2026-09-27T18:00:00'), totalAmount: 12000 })
    expect(message).toContain('T1-7')
    expect(message).toContain('12,000원')
  })
})
