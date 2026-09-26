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
  it('승인대기면 거절과 승인 (O28)', () => {
    const l = buttonsOf(order('PENDING_APPROVAL', 'UNPAID'), true)
    expect(l).toContain('거절')
    expect(l).toContain('승인')
    expect(l).not.toContain('취소')
    expect(l).not.toContain('완료')
  })

  it('onApprove·onReject가 없으면(승인대기 전용 콜백 미전달) 버튼을 그리지 않는다', () => {
    const tree = OrderCard({
      order: order('PENDING_APPROVAL', 'UNPAID'), now: Date.parse('2026-09-21T18:10:00'), pending: false,
      onComplete: vi.fn(), onCancel: vi.fn(), onRestore: vi.fn(),
    })
    expect(labels(tree)).not.toContain('승인')
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
})
