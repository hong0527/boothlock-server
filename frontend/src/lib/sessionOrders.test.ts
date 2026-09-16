import { describe, expect, it } from 'vitest'
import { isOrderOfSession, isUnpaid, ordersOfSession, unpaidTotal } from './sessionOrders'

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
