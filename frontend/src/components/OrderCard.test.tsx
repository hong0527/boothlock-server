import { Children, isValidElement, type ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import OrderCard from './OrderCard'
import type { OrderStatus, OrderSummary, PaymentStatus } from '../types/dashboard'

type Props = { children?: ReactNode; onClick?: () => void }

function labels(node: ReactNode, out: string[] = []): string[] {
  for (const child of Children.toArray(node)) {
    if (typeof child === 'string') { if (child.trim()) out.push(child.trim()); continue }
    if (!isValidElement<Props>(child)) continue
    labels(child.props.children, out)
  }
  return out
}

function order(status: OrderStatus, paymentStatus: PaymentStatus): OrderSummary {
  return {
    orderId: 1, orderNo: 'A-1', status, paymentStatus, paymentMethod: null,
    totalAmount: 1000, items: [], createdAt: '2026-09-21T18:00:00',
    tableLabel: 'A-1', manual: false, sessionId: 1,
  }
}

function buttonsOf(o: OrderSummary, withRefund: boolean) {
  const tree = OrderCard({
    order: o, now: Date.parse('2026-09-21T18:10:00'), pending: false,
    onApprove: vi.fn(), onReject: vi.fn(),
    onComplete: vi.fn(), onCancel: vi.fn(), onRestore: vi.fn(),
    ...(withRefund ? { onRefundDone: vi.fn() } : {}),
  })
  return labels(tree)
}

describe('주문 카드 버튼', () => {
  it('승인대기면 거절과 주문 승인 (O28)', () => {
    const l = buttonsOf(order('PENDING_APPROVAL', 'UNPAID'), true)
    expect(l).toContain('거절')
    expect(l).toContain('주문 승인')
    expect(l).not.toContain('취소')
    expect(l).not.toContain('완료')
  })

  it('onApprove·onReject가 없으면(승인대기 전용 콜백 미전달) 버튼을 그리지 않는다', () => {
    const tree = OrderCard({
      order: order('PENDING_APPROVAL', 'UNPAID'), now: Date.parse('2026-09-21T18:10:00'), pending: false,
      onComplete: vi.fn(), onCancel: vi.fn(), onRestore: vi.fn(),
    })
    expect(labels(tree)).not.toContain('주문 승인')
  })

  it('진행 중이면 취소와 완료', () => {
    const l = buttonsOf(order('RECEIVED', 'UNPAID'), true)
    expect(l).toContain('취소')
    expect(l).toContain('완료')
    expect(l).not.toContain('환불 완료')
    expect(l).not.toContain('승인')
  })

  it('취소됐고 환불이 필요하면 환불 완료가 보인다', () => {
    expect(buttonsOf(order('CANCELED', 'REFUND_NEEDED'), true)).toContain('환불 완료')
  })

  it('취소됐지만 미입금이면 환불할 게 없다', () => {
    expect(buttonsOf(order('CANCELED', 'UNPAID'), true)).not.toContain('환불 완료')
  })

  it('이미 환불했으면 다시 안 보인다', () => {
    expect(buttonsOf(order('CANCELED', 'REFUNDED'), true)).not.toContain('환불 완료')
  })

  it('ADMIN이 아니면 환불필요여도 안 보인다', () => {
    expect(buttonsOf(order('CANCELED', 'REFUND_NEEDED'), false)).not.toContain('환불 완료')
  })

  it('완료 상태에서 환불필요여도 처리할 수 있다', () => {
    // 입금 후 완료했다가 취소·환불이 필요해진 경우도 막다른 길이 되면 안 된다
    expect(buttonsOf(order('DONE', 'REFUND_NEEDED'), true)).toContain('환불 완료')
  })

  // 결제 확인은 카드가 아니라 테이블 화면 결제 모달(O24)에서 한다(2026-09-28 카드 버튼 제거)
  it('어떤 상태에서도 카드에는 결제 확인 버튼이 없다', () => {
    for (const status of ['PENDING_APPROVAL', 'RECEIVED', 'DONE'] as const) {
      expect(buttonsOf(order(status, 'UNPAID'), true)).not.toContain('결제 확인')
    }
  })
})

describe('주문 카드 머리', () => {
  it('진행 탭에서 승인대기와 진행중을 뱃지로 가른다', () => {
    expect(buttonsOf(order('PENDING_APPROVAL', 'UNPAID'), true)).toContain('승인 대기')
    expect(buttonsOf(order('RECEIVED', 'UNPAID'), true)).toContain('진행중')
    expect(buttonsOf(order('DONE', 'UNPAID'), true)).not.toContain('진행중')
  })

  it('주문번호·금액은 카드에 보이지 않는다', () => {
    const l = buttonsOf({ ...order('RECEIVED', 'UNPAID'), orderNo: 'T1-17', totalAmount: 12000 }, true)
    expect(l).not.toContain('T1-17')
    expect(l).not.toContain('12,000원')
  })

  it('결제 상태 뱃지는 그대로 보인다', () => {
    expect(buttonsOf(order('RECEIVED', 'UNPAID'), true)).toContain('미결제')
  })
})

describe('추가주문 배지', () => {
  it('additionalOrder가 true면 배지를 그린다', () => {
    const tree = OrderCard({
      order: order('RECEIVED', 'UNPAID'), now: Date.parse('2026-09-21T18:10:00'), pending: false,
      onComplete: vi.fn(), onCancel: vi.fn(), onRestore: vi.fn(), additionalOrder: true,
    })
    expect(labels(tree)).toContain('추가주문')
  })

  it('additionalOrder를 안 주면(기본값) 배지가 없다', () => {
    expect(buttonsOf(order('RECEIVED', 'UNPAID'), true)).not.toContain('추가주문')
  })
})
