import type { OrderSummary } from '../types/customer'

export type CustomerOrderBadge = {
  label: string
  /** 뱃지 색 구분 — 화면 클래스는 호출부가 정한다 */
  tone: 'pending' | 'active' | 'done' | 'canceled'
  /** 입금된 뒤 취소·거절된 주문의 환불 진행 — 손님이 "보낸 돈은 어떻게 되나"를 바로 알게 한다 */
  refundNote: string | null
}

/**
 * 손님 주문내역(C4) 주문별 상태 뱃지. C4는 status·paymentStatus만 주고 누가 취소했는지(손님 C5·운영자 거절 O13·퇴실/유휴 자동 거절)는
 * 주지 않으므로 CANCELED는 "취소·거절" 하나로 보인다. 승인대기(O28)는 운영자 승인 전이라 조리가 시작되지 않았음을 알린다
 */
export function customerOrderBadge(order: Pick<OrderSummary, 'status' | 'paymentStatus'>): CustomerOrderBadge {
  const refundNote =
    order.paymentStatus === 'REFUND_NEEDED' ? '환불 예정' : order.paymentStatus === 'REFUNDED' ? '환불 완료' : null
  switch (order.status) {
    case 'PENDING_APPROVAL':
      return { label: '승인대기', tone: 'pending', refundNote }
    case 'RECEIVED':
      return { label: '접수', tone: 'active', refundNote }
    case 'DONE':
      return { label: '완료', tone: 'done', refundNote }
    case 'CANCELED':
      return { label: '취소·거절', tone: 'canceled', refundNote }
  }
}
