import { describe, expect, it } from 'vitest'
import { orderedForTab } from './dashboardOrders'
import type { OrderStatus, OrderSummary } from '../types/dashboard'

/** 서버(O10)가 주는 순서를 흉내낸다 — 최신 주문이 먼저 */
function order(orderNo: string, createdAt: string, status: OrderStatus = 'RECEIVED'): OrderSummary {
  return {
    orderId: Number(orderNo.replace(/\D/g, '')),
    orderNo,
    status,
    paymentStatus: 'UNPAID',
    paymentMethod: null,
    totalAmount: 1000,
    items: [],
    createdAt,
    tableLabel: 'A-1',
    manual: false,
    sessionId: 1,
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
