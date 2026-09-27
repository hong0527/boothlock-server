import { describe, expect, it } from 'vitest'
import type { OrderSummary } from '../types/customer'
import { pendingSeatFee } from './seatFee'

const order = (status: OrderSummary['status'], seatFee: boolean): OrderSummary =>
  ({
    orderId: 1, orderNo: 'A-1', status, paymentStatus: 'UNPAID', totalAmount: 0, canCancel: false, createdAt: '',
    payment: {} as OrderSummary['payment'],
    items: [
      { menuId: 1, menuName: '김치전', unitPrice: 8000, qty: 1, itemType: 'MENU' },
      ...(seatFee ? [{ menuId: null, menuName: '자릿세', unitPrice: 3000, qty: 4, itemType: 'SEAT_FEE' as const }] : []),
    ],
  }) as OrderSummary

describe('pendingSeatFee — 주문 확인 화면의 자릿세 미리보기', () => {
  it('첫 주문이면 인원수 × 3,000원', () => {
    expect(pendingSeatFee(4, [])).toBe(12_000)
  })
  it('이미 청구됐으면 0', () => {
    expect(pendingSeatFee(4, [order('RECEIVED', true)])).toBe(0)
    expect(pendingSeatFee(4, [order('DONE', true)])).toBe(0)
  })
  it('자릿세가 붙은 주문이 취소됐으면 다시 붙는다(서버와 같은 규칙)', () => {
    expect(pendingSeatFee(4, [order('CANCELED', true), order('RECEIVED', false)])).toBe(12_000)
  })
  it('주문내역을 못 불러왔으면 판단 보류(null)', () => {
    expect(pendingSeatFee(4, null)).toBeNull()
  })
  it('C1이 자릿세를 이미 냈다고 알려 주면(유휴 인계로 이어받음) 주문내역에 자릿세가 없어도 0', () => {
    expect(pendingSeatFee(3, [], true)).toBe(0)
    // 주문내역을 못 불러와도 서버가 이미 알려 준 값이면 보류하지 않는다
    expect(pendingSeatFee(3, null, true)).toBe(0)
  })
  it('C1이 안 냈다고 하면 지금처럼 주문내역으로 판단한다', () => {
    expect(pendingSeatFee(3, [], false)).toBe(9_000)
    expect(pendingSeatFee(3, [order('RECEIVED', true)], false)).toBe(0)
  })
  it('인원수가 없으면 0(서버가 인원 선택으로 돌려보낸다)', () => {
    expect(pendingSeatFee(undefined, [])).toBe(0)
  })
})
