import { describe, expect, it } from 'vitest'
import {
  aggregateSessionOrders,
  isOrderOfSession,
  isUnpaid,
  ordersOfSession,
  pendingApprovalSummary,
  unpaidTotal,
} from './sessionOrders'

const session = { id: 42 }

describe('isOrderOfSession — O10 sessionId와 O3 session.id 대조', () => {
  it('세션 id가 같은 주문만 현재 손님 주문으로 본다', () => {
    expect(isOrderOfSession({ sessionId: 42 }, session)).toBe(true)
    expect(isOrderOfSession({ sessionId: 41 }, session)).toBe(false)
  })

  it('세션이 없으면(빈 테이블·정리 필요) 아무 주문도 고르지 않는다', () => {
    expect(isOrderOfSession({ sessionId: 42 }, null)).toBe(false)
    expect(isOrderOfSession({ sessionId: 42 }, undefined)).toBe(false)
  })

  it('테이블 미지정 수기 주문(sessionId null)은 어느 세션에도 붙지 않는다', () => {
    expect(isOrderOfSession({ sessionId: null }, session)).toBe(false)
  })
})

describe('ordersOfSession', () => {
  it('이전 세션·수기 주문을 걸러낸다', () => {
    const orders = [
      { orderId: 1, sessionId: 41 },
      { orderId: 2, sessionId: 42 },
      { orderId: 3, sessionId: null },
      { orderId: 4, sessionId: 42 },
    ]
    expect(ordersOfSession(orders, session).map((o) => o.orderId)).toEqual([2, 4])
  })
})

describe('isUnpaid / unpaidTotal — 백엔드 UnpaidOrderRule과 같은 정의', () => {
  const orders = [
    { status: 'RECEIVED', paymentStatus: 'UNPAID', totalAmount: 13000 },
    { status: 'RECEIVED', paymentStatus: 'PAID', totalAmount: 5000 },
    { status: 'DONE', paymentStatus: 'UNPAID', totalAmount: 7000 },
    { status: 'DONE', paymentStatus: 'PAID', totalAmount: 6000 },
    { status: 'CANCELED', paymentStatus: 'UNPAID', totalAmount: 9000 },
    { status: 'RECEIVED', paymentStatus: 'REFUND_NEEDED', totalAmount: 4000 },
    { status: 'RECEIVED', paymentStatus: 'UNPAID', totalAmount: 8000 },
  ] as const

  it('RECEIVED·UNPAID와 DONE·UNPAID를 합산한다 (PAID·CANCELED·환불 제외)', () => {
    expect(unpaidTotal([...orders])).toBe(28000)
  })

  it('조합표', () => {
    expect(isUnpaid({ status: 'RECEIVED', paymentStatus: 'UNPAID' })).toBe(true)
    expect(isUnpaid({ status: 'DONE', paymentStatus: 'UNPAID' })).toBe(true)
    expect(isUnpaid({ status: 'CANCELED', paymentStatus: 'UNPAID' })).toBe(false)
    expect(isUnpaid({ status: 'RECEIVED', paymentStatus: 'PAID' })).toBe(false)
    expect(isUnpaid({ status: 'DONE', paymentStatus: 'PAID' })).toBe(false)
    expect(isUnpaid({ status: 'DONE', paymentStatus: 'REFUND_NEEDED' })).toBe(false)
  })

  it('대상이 없으면 0', () => {
    expect(unpaidTotal([])).toBe(0)
  })
})

describe('pendingApprovalSummary — 결제 모달 "승인대기 N건 · X원"', () => {
  it('승인대기만 세고 금액을 더한다 — 미결제 합계(unpaidTotal)와 겹치지 않는다', () => {
    const orders = [
      { status: 'PENDING_APPROVAL', paymentStatus: 'UNPAID', totalAmount: 8000 },
      { status: 'PENDING_APPROVAL', paymentStatus: 'PAID', totalAmount: 3000 },
      { status: 'RECEIVED', paymentStatus: 'UNPAID', totalAmount: 13000 },
      { status: 'CANCELED', paymentStatus: 'UNPAID', totalAmount: 9000 },
    ] as const
    expect(pendingApprovalSummary([...orders])).toEqual({ count: 2, amount: 11000, paidCount: 1 })
    expect(unpaidTotal([...orders])).toBe(13000)
  })

  it('승인대기가 없으면 0건 0원', () => {
    expect(pendingApprovalSummary([])).toEqual({ count: 0, amount: 0, paidCount: 0 })
  })
})

describe('aggregateSessionOrders — 테이블-홈 카드 집계(M4)', () => {
  const order = (
    orderId: number,
    status: 'PENDING_APPROVAL' | 'RECEIVED' | 'DONE' | 'CANCELED',
    totalAmount: number,
    createdAt: string,
    sessionId: number | null = 42,
  ) => ({ orderId, sessionId, status, totalAmount, createdAt, items: [{ menuName: `메뉴${orderId}`, qty: 1 }] })

  it('승인대기는 합계·항목에서 빼고 건수만 센다 — 결제 모달과 같은 기준', () => {
    const result = aggregateSessionOrders(session, [
      order(1, 'RECEIVED', 10000, '2026-09-27T18:10:00+09:00'),
      order(2, 'PENDING_APPROVAL', 5000, '2026-09-27T18:20:00+09:00'),
      order(3, 'DONE', 7000, '2026-09-27T18:15:00+09:00'),
      order(4, 'CANCELED', 9000, '2026-09-27T18:00:00+09:00'),
      order(5, 'PENDING_APPROVAL', 2000, '2026-09-27T18:05:00+09:00', 41),   // 이전 세션
    ] as never)
    expect(result.orderTotal).toBe(17000)
    expect(result.pendingApprovalCount).toBe(1)
    expect(result.orderItems.map((i) => i.menuName).sort()).toEqual(['메뉴1', '메뉴3'])
  })

  it('첫 주문 시각은 승인대기도 포함한다(앉아 있던 시간 기준) — 취소는 제외', () => {
    const result = aggregateSessionOrders(session, [
      order(1, 'RECEIVED', 10000, '2026-09-27T18:10:00+09:00'),
      order(2, 'PENDING_APPROVAL', 5000, '2026-09-27T18:05:00+09:00'),
      order(3, 'CANCELED', 9000, '2026-09-27T18:00:00+09:00'),
    ] as never)
    expect(result.firstOrderAt).toBe('2026-09-27T18:05:00+09:00')
  })

  it('세션이 없으면 빈 카드', () => {
    expect(aggregateSessionOrders(null, [order(1, 'PENDING_APPROVAL', 5000, '2026-09-27T18:05:00+09:00')] as never)).toEqual({
      orderItems: [],
      orderTotal: 0,
      firstOrderAt: null,
      pendingApprovalCount: 0,
    })
  })
})
