import { describe, expect, it } from 'vitest'
import type { OrderSummary } from '../types/customer'
import { pendingSeatFee } from './seatFee'

const order = (withSeatFee: boolean): OrderSummary =>
  ({
    orderId: 1,
    orderNo: 'A3-1',
    status: 'DONE',
    paymentStatus: 'UNPAID',
    items: withSeatFee
      ? [{ menuId: null, menuName: '자릿세', unitPrice: 3000, qty: 4, itemType: 'SEAT_FEE' }]
      : [{ menuId: 1, menuName: '김치전', unitPrice: 8000, qty: 1, itemType: 'MENU' }],
  }) as OrderSummary

describe('pendingSeatFee — 주문 확인 화면의 자릿세 미리보기', () => {
  it('첫 주문이면 부스 1인당 금액 × 인원수', () => {
    expect(pendingSeatFee(4, 3000, [])).toBe(12_000)
    expect(pendingSeatFee(2, 5000, [order(false)])).toBe(10_000)   // 운영자 수기 주문만 있던 세션
  })
  it('이미 자릿세 주문이 있으면 0', () => {
    expect(pendingSeatFee(4, 3000, [order(true)])).toBe(0)
  })
  it('C1이 처리됐다고 알려 준 세션(유휴 인계·면제)은 0', () => {
    expect(pendingSeatFee(4, 3000, [], true)).toBe(0)
    expect(pendingSeatFee(4, 3000, null, true)).toBe(0)
  })
  it('부스가 자릿세를 안 받거나 인원이 없으면 0', () => {
    expect(pendingSeatFee(4, 0, [])).toBe(0)
    expect(pendingSeatFee(undefined, 3000, [])).toBe(0)
  })
  it('주문내역을 못 불러오면 판단 불가(null)', () => {
    expect(pendingSeatFee(4, 3000, null)).toBeNull()
  })
})
