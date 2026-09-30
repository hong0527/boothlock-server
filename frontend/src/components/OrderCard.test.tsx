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

function order(
  status: OrderStatus,
  paymentStatus: PaymentStatus,
  items: OrderSummary['items'] = [],
): OrderSummary {
  return {
    orderId: 1, orderNo: 'A-1', status, paymentStatus, paymentMethod: null,
    totalAmount: 1000, items, createdAt: '2026-09-21T18:00:00',
    tableLabel: 'A-1', manual: false, sessionId: 1,
  }
}

const MENU_ITEM: OrderSummary['items'][number] = {
  itemId: 1, menuId: 1, menuName: '떡볶이', unitPrice: 4000, qty: 1, itemType: 'MENU',
}
const SEAT_FEE_ITEM: OrderSummary['items'][number] = {
  itemId: 2, menuId: null, menuName: '자릿세', unitPrice: 1000, qty: 3, itemType: 'SEAT_FEE',
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

  // 자릿세·기타 항목만 남은 완료 주문은 되돌리기가 항상 409로 막힌다(OrderEntity.restore —
  // "조리할 메뉴가 없는 주문은 접수로 되돌릴 수 없다"). 그 경우에만 되돌리기→취소 두 단계를 거치지 않고
  // 바로 지울 수 있어야 한다(2026-09-30 파일럿 현장 피드백, 백엔드 O13은 이미 지원). 메뉴가 남은 일반
  // 완료 주문은 되돌리기가 정상 동작하므로 범위를 이 경우로만 좁힌다
  it('메뉴 항목이 없는 완료 주문(자릿세만)에는 되돌리기 옆에 취소도 바로 보인다', () => {
    const l = buttonsOf(order('DONE', 'UNPAID', [SEAT_FEE_ITEM]), true)
    expect(l).toContain('취소')
    expect(l).toContain('되돌리기')
  })

  it('메뉴가 남은 일반 완료 주문에는 취소가 안 보인다(되돌리기가 정상 동작해서)', () => {
    const l = buttonsOf(order('DONE', 'UNPAID', [MENU_ITEM]), true)
    expect(l).not.toContain('취소')
    expect(l).toContain('되돌리기')
  })

  it('이미 취소된 카드에는 취소 버튼이 다시 안 보인다(눌러도 항상 실패라서)', () => {
    expect(buttonsOf(order('CANCELED', 'UNPAID', [SEAT_FEE_ITEM]), true)).not.toContain('취소')
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
