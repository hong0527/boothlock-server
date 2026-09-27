import { describe, expect, it } from 'vitest'
import { depositorGuide } from './depositorName'
import type { OrderSummary } from '../types/customer'

function unpaid(orderNo: string): OrderSummary {
  return {
    orderId: 1, orderNo, status: 'RECEIVED', paymentStatus: 'UNPAID', totalAmount: 1000, items: [],
    payment: {
      bankAccount: '테스트은행 001', depositorName: null,
      depositorNameRule: `입금자명을 '이름+${orderNo}'로 입력해주세요 (예: 김철수${orderNo})`,
    },
    canCancel: false, createdAt: '2026-09-27T18:00:00',
  }
}

describe('depositorGuide', () => {
  it('미결제 주문이 없으면 안내하지 않는다', () => {
    expect(depositorGuide([])).toBeNull()
  })

  it('서버가 준 규칙과 주문번호를 그대로 쓴다 — 프론트가 규칙을 새로 만들지 않는다', () => {
    expect(depositorGuide([unpaid('T12-3')])).toEqual({
      orderNo: 'T12-3',
      rule: "입금자명을 '이름+T12-3'로 입력해주세요 (예: 김철수T12-3)",
      multiple: false,
    })
  })

  it('여러 건이면 가장 최근(C4 첫 번째) 주문번호를 쓰고 합계 이체임을 표시한다', () => {
    const guide = depositorGuide([unpaid('T12-5'), unpaid('T12-3')])
    expect(guide?.orderNo).toBe('T12-5')
    expect(guide?.multiple).toBe(true)
  })
})
