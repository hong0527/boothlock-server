import { describe, expect, it } from 'vitest'
import type { OrderSummary } from '../types/customer'
import { minOrderShortfall } from './minOrder'

const order = (status: OrderSummary['status'], itemType: 'MENU' | 'SEAT_FEE', unitPrice = 8000, qty = 1): OrderSummary =>
  ({
    orderId: 1,
    orderNo: 'A3-1',
    status,
    paymentStatus: 'UNPAID',
    items: [{ menuId: itemType === 'MENU' ? 1 : null, menuName: '김치전', unitPrice, qty, itemType }],
  }) as OrderSummary

describe('minOrderShortfall — 장바구니의 최소주문금액 안내(일행 메뉴 합계 기준)', () => {
  it('첫 주문이면 최소금액까지 모자란 금액', () => {
    expect(minOrderShortfall(20_000, 16_000, [])).toBe(4_000)
    expect(minOrderShortfall(20_000, 24_000, [])).toBe(0)
  })
  it('이미 주문한 메뉴 합계가 최소금액을 넘었으면 추가 주문은 막지 않는다', () => {
    expect(minOrderShortfall(20_000, 5_000, [order('RECEIVED', 'MENU', 8000, 3)])).toBe(0)
  })
  it('살아 있는 메뉴 합계가 모자라면 그만큼만 더 담으면 된다', () => {
    expect(minOrderShortfall(20_000, 5_000, [order('RECEIVED', 'MENU')])).toBe(7_000)
  })
  it('유휴 인계로 이어받은 앞 세션 메뉴 합계도 센다', () => {
    expect(minOrderShortfall(20_000, 5_000, [], 16_000)).toBe(0)
    expect(minOrderShortfall(20_000, 5_000, [], 8_000)).toBe(7_000)
  })
  it('취소된 주문·자릿세만 든 주문은 첫 주문 면제가 아니다', () => {
    expect(minOrderShortfall(20_000, 5_000, [order('CANCELED', 'MENU')])).toBe(15_000)
    expect(minOrderShortfall(20_000, 5_000, [order('DONE', 'SEAT_FEE')])).toBe(15_000)
  })
  it('제한 없음(0·예전 세션)이거나 주문내역을 모르면 막지 않는다', () => {
    expect(minOrderShortfall(0, 5_000, [])).toBe(0)
    expect(minOrderShortfall(undefined, 5_000, [])).toBe(0)
    expect(minOrderShortfall(20_000, 5_000, null)).toBe(0)
    expect(minOrderShortfall(20_000, 5_000, undefined)).toBe(0)
  })
})
