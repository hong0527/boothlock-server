import { describe, expect, it } from 'vitest'
import { alertTitle, diffArrivals, snapshotOf } from './newArrivals'
import type { CallSummary, OrderSummary } from '../types/dashboard'

function pending(orderId: number): OrderSummary {
  return {
    orderId, orderNo: `T1-${orderId}`, status: 'PENDING_APPROVAL',
    paymentStatus: 'UNPAID', paymentMethod: null, totalAmount: 1000, items: [],
    createdAt: '2026-09-27T18:00:00', tableLabel: 'T-1', manual: false, sessionId: 1,
  }
}
function call(callId: number): CallSummary {
  return { callId, tableLabel: 'T-1', reason: 'HELP', createdAt: '2026-09-27T18:00:00' }
}

describe('diffArrivals', () => {
  it('첫 조회는 이미 쌓여 있던 것이 있어도 알리지 않는다', () => {
    expect(diffArrivals(null, snapshotOf([pending(1), pending(2)], [call(1)]))).toEqual({ newPendingOrders: 0, newCalls: 0 })
  })

  it('이전에 없던 승인대기 주문·호출만 센다', () => {
    const prev = snapshotOf([pending(1)], [call(1)])
    const next = snapshotOf([pending(1), pending(2), pending(3)], [call(1), call(2)])
    expect(diffArrivals(prev, next)).toEqual({ newPendingOrders: 2, newCalls: 1 })
  })

  it('승인·거절·확인으로 빠지기만 하면 알리지 않는다', () => {
    const prev = snapshotOf([pending(1), pending(2)], [call(1)])
    expect(diffArrivals(prev, snapshotOf([pending(2)], []))).toEqual({ newPendingOrders: 0, newCalls: 0 })
  })

  it('개수가 같아도 하나 빠지고 새로 하나 들어왔으면 알린다', () => {
    const prev = snapshotOf([pending(1), pending(2)], [])
    expect(diffArrivals(prev, snapshotOf([pending(2), pending(3)], []))).toEqual({ newPendingOrders: 1, newCalls: 0 })
  })

  it('확인했던 테이블이 다시 호출하면(새 callId) 새 호출로 잡는다', () => {
    const prev = snapshotOf([], [call(1)])
    expect(diffArrivals(prev, snapshotOf([], [call(2)]))).toEqual({ newPendingOrders: 0, newCalls: 1 })
  })
})

describe('alertTitle', () => {
  it('승인대기가 있으면 건수를 제목 앞에 단다', () => {
    expect(alertTitle(3, '부스락')).toBe('(3) 승인대기 · 부스락')
  })

  it('0건이면 원래 제목으로 되돌린다', () => {
    expect(alertTitle(0, '부스락')).toBe('부스락')
  })
})
