import { describe, expect, it } from 'vitest'
import { customerOrderBadge } from './customerOrderStatus'

describe('customerOrderBadge — C4 주문 상태 뱃지', () => {
  it('주문 축 네 상태를 각각 승인대기·접수·완료·취소·거절로 보여준다', () => {
    expect(customerOrderBadge({ status: 'PENDING_APPROVAL', paymentStatus: 'UNPAID' }).label).toBe('승인대기')
    expect(customerOrderBadge({ status: 'RECEIVED', paymentStatus: 'UNPAID' }).label).toBe('접수')
    expect(customerOrderBadge({ status: 'DONE', paymentStatus: 'PAID' }).label).toBe('완료')
    expect(customerOrderBadge({ status: 'CANCELED', paymentStatus: 'UNPAID' }).label).toBe('취소·거절')
  })

  it('입금 뒤 취소·거절된 주문은 환불 진행을 함께 알린다', () => {
    expect(customerOrderBadge({ status: 'CANCELED', paymentStatus: 'REFUND_NEEDED' }).refundNote).toBe('환불 예정')
    expect(customerOrderBadge({ status: 'CANCELED', paymentStatus: 'REFUNDED' }).refundNote).toBe('환불 완료')
    expect(customerOrderBadge({ status: 'CANCELED', paymentStatus: 'UNPAID' }).refundNote).toBeNull()
    expect(customerOrderBadge({ status: 'RECEIVED', paymentStatus: 'PAID' }).refundNote).toBeNull()
  })
})
