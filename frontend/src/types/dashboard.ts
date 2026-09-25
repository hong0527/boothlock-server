/** 백엔드 DashboardResponse (dashboard/dto/DashboardResponse.java)와 1:1로 맞춘 타입 */
export type OrderStatus = 'RECEIVED' | 'DONE' | 'CANCELED'

/** itemType이 'SEAT_FEE'(자릿세, 명세서 밖)면 menuId는 null — 실제 메뉴가 아니다. 서버가 이미 수정·취소를 막지만
 * 프론트도 결제창 버튼을 미리 비활성화한다(PaymentModal) */
export type OrderItemSummary = {
  itemId: number
  menuId: number | null
  menuName: string
  unitPrice: number
  qty: number
  itemType: 'MENU' | 'SEAT_FEE'
}

export type PaymentStatus = 'UNPAID' | 'PAID' | 'REFUND_NEEDED' | 'REFUNDED'

/** 백엔드 order/domain/PaymentMethod.java — 팀 결정(9/16): 결제는 계좌이체만 쓰므로 프론트는 BANK_TRANSFER만 보낸다 */
export type PaymentMethod = 'BANK_TRANSFER' | 'CASH'

export type OrderSummary = {
  orderId: number
  orderNo: string
  status: OrderStatus
  paymentStatus: PaymentStatus
  paymentMethod: PaymentMethod | null
  totalAmount: number
  items: OrderItemSummary[]
  createdAt: string
  /** 수기 주문(테이블 미지정)은 세션이 없어서 null일 수 있음 */
  tableLabel: string | null
  /** O14 수기 주문으로 만들어졌는지 — 백엔드 필드명 그대로(manual). 명세 예시의 isManual과 다르다 */
  manual: boolean
  /** 주문이 붙은 테이블 세션 PK(O3 session.id와 같은 값). 테이블 미지정 수기 주문은 null */
  sessionId: number | null
}

/** O10 응답의 미확인 호출 1건 (DashboardResponse.CallSummary). O15 확인(ack)하면 목록에서 빠진다 */
export type CallSummary = {
  callId: number
  tableLabel: string
  reason: 'HELP' | 'WATER' | 'ETC'
  createdAt: string
}

/** O10 응답 전체 — 주문 + 미확인 호출을 폴링 한 번으로 받는다 */
export type DashboardResponse = {
  orders: OrderSummary[]
  calls: CallSummary[]
}

/** O15 응답 (dashboard/dto/CallAckResponse.java) */
export type CallAckResponse = {
  callId: number
  acked: boolean
}

/** O24 응답 (dashboard/dto/TablePaymentResponse.java) — 이번에 PAID로 바뀐 주문과 합계 */
export type TablePaymentResponse = {
  orders: OrderSummary[]
  totalAmount: number
}

export const PAYMENT_STATUS_LABEL: Record<PaymentStatus, string> = {
  UNPAID: '미결제',
  PAID: '입금확인',
  REFUND_NEEDED: '환불필요',
  REFUNDED: '환불완료',
}

export const CALL_REASON_LABEL: Record<CallSummary['reason'], string> = {
  HELP: '직원 호출',
  WATER: '물·수저',
  ETC: '기타',
}
