import { describe, expect, it } from 'vitest'
import { alertTitle, diffArrivals, LATE_ALERT_MS, snapshotOf } from './newArrivals'
import type { CallSummary, OrderSummary } from '../types/dashboard'

function pending(orderId: number, createdAt = '2026-09-27T18:00:00'): OrderSummary {
  return {
    orderId, orderNo: `T1-${orderId}`, status: 'PENDING_APPROVAL',
    paymentStatus: 'UNPAID', paymentMethod: null, totalAmount: 1000, items: [],
    createdAt, tableLabel: 'T-1', manual: false, sessionId: 1,
  }
}
function call(callId: number, createdAt = '2026-09-27T18:00:00'): CallSummary {
  return { callId, tableLabel: 'T-1', reason: 'HELP', createdAt }
}

// 서버 시각(응답 Date 헤더)과 생긴 시각 — 서버가 주는 모양(+09:00 오프셋)으로
const SERVER_NOW = Date.parse('2026-09-29T12:00:00+09:00')
const secondsAgo = (seconds: number) =>
  new Date(SERVER_NOW - seconds * 1000 + 9 * 3600_000).toISOString().replace('Z', '+09:00')

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

describe('diffArrivals — 따라잡기 조회(suppressLate)에서만 늦은 것을 소리로 알리지 않는다(서버 시각 기준)', () => {
  const CATCH_UP = { suppressLate: true }

  it('따라잡기 조회에서 생긴 지 20초가 넘은 주문·호출은 새로 보여도 세지 않는다 — 뒤에 있던 탭이 돌아와 알게 된 경우', () => {
    const prev = snapshotOf([pending(1, secondsAgo(90))], [], SERVER_NOW)
    const next = snapshotOf([pending(1, secondsAgo(90)), pending(2, secondsAgo(45))], [call(7, secondsAgo(21))], SERVER_NOW)
    expect(diffArrivals(prev, next, CATCH_UP)).toEqual({ newPendingOrders: 0, newCalls: 0 })
  })

  it('평소 조회(suppressLate 없음)는 20초가 넘었어도 센다 — 느린 회선·실패 뒤 복구·숨겨진 탭의 느린 주기', () => {
    const prev = snapshotOf([], [], SERVER_NOW)
    const next = snapshotOf([pending(2, secondsAgo(45))], [call(7, secondsAgo(120))], SERVER_NOW)
    expect(diffArrivals(prev, next)).toEqual({ newPendingOrders: 1, newCalls: 1 })
    expect(diffArrivals(prev, next, { suppressLate: false })).toEqual({ newPendingOrders: 1, newCalls: 1 })
  })

  it('따라잡기 조회에서도 20초 안에 생긴 것은 센다(20초 딱이면 아직 센다)', () => {
    const prev = snapshotOf([], [], SERVER_NOW)
    const next = snapshotOf(
      [pending(2, secondsAgo(3)), pending(3, secondsAgo(LATE_ALERT_MS / 1000))], [call(7, secondsAgo(0))], SERVER_NOW,
    )
    expect(diffArrivals(prev, next, CATCH_UP)).toEqual({ newPendingOrders: 2, newCalls: 1 })
  })

  it('오래된 것과 방금 것이 같이 들어오면 방금 것만 센다', () => {
    const prev = snapshotOf([], [], SERVER_NOW)
    const next = snapshotOf(
      [pending(2, secondsAgo(120)), pending(3, secondsAgo(2))],
      [call(7, secondsAgo(60)), call(8, secondsAgo(1)), call(9, secondsAgo(30))],
      SERVER_NOW,
    )
    expect(diffArrivals(prev, next, CATCH_UP)).toEqual({ newPendingOrders: 1, newCalls: 1 })
  })

  it('따라잡기에서 세지 않은 것은 스냅샷에는 남아, 다음 평소 조회에서도 다시 세지 않는다', () => {
    const first = snapshotOf([], [], SERVER_NOW)
    const late = snapshotOf([pending(2, secondsAgo(60))], [], SERVER_NOW)
    expect(diffArrivals(first, late, CATCH_UP)).toEqual({ newPendingOrders: 0, newCalls: 0 })
    const later = snapshotOf([pending(2, secondsAgo(65))], [], SERVER_NOW + 5000)
    expect(diffArrivals(late, later)).toEqual({ newPendingOrders: 0, newCalls: 0 })
  })

  it('실제 서버 createdAt 형식(소수점 6자리 +09:00)도 읽는다', () => {
    const serverNow = Date.parse('Tue, 29 Sep 2026 03:00:30 GMT') // = 12:00:30 KST
    const prev = snapshotOf([], [], serverNow)
    const next = snapshotOf(
      [pending(2, '2026-09-29T12:00:09.123456+09:00'), pending(3, '2026-09-29T12:00:25.999999+09:00')],
      [call(7, '2026-09-29T11:59:00.000001+09:00')],
      serverNow,
    )
    expect(next.orderCreatedAt?.get(3)).toBe(Date.parse('2026-09-29T03:00:25.999Z'))
    // 주문 2는 21초 전(무음), 주문 3은 5초 전(알림), 호출 7은 90초 전(무음)
    expect(diffArrivals(prev, next, CATCH_UP)).toEqual({ newPendingOrders: 1, newCalls: 0 })
  })

  it('서버 시각을 모르면(Date 헤더 없음·못 읽음) 따라잡기 조회라도 가리지 않고 센다 — 새 알림을 놓치지 않게', () => {
    const prev = snapshotOf([], [])
    const next = snapshotOf([pending(2, secondsAgo(300))], [call(7, secondsAgo(300))])
    expect(diffArrivals(prev, next, CATCH_UP)).toEqual({ newPendingOrders: 1, newCalls: 1 })
  })

  it('생긴 시각을 못 읽는 항목은 가리지 않고 센다', () => {
    const prev = snapshotOf([], [], SERVER_NOW)
    const next = snapshotOf([pending(2, 'garbage')], [call(7, '')], SERVER_NOW)
    expect(diffArrivals(prev, next, CATCH_UP)).toEqual({ newPendingOrders: 1, newCalls: 1 })
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
