/** 백엔드 DashboardResponse (dashboard/dto/DashboardResponse.java)와 1:1로 맞춘 타입 */
export type OrderStatus = 'RECEIVED' | 'DONE' | 'CANCELED'

export type OrderItemSummary = {
  itemId: number
  menuId: number
  menuName: string
  unitPrice: number
  qty: number
}

export type PaymentStatus = 'UNPAID' | 'PAID' | 'REFUND_NEEDED' | 'REFUNDED'

export type OrderSummary = {
  orderId: number
  orderNo: string
  status: OrderStatus
  paymentStatus: PaymentStatus
  totalAmount: number
  items: OrderItemSummary[]
  createdAt: string
  /** 수기 주문(테이블 미지정)은 세션이 없어서 null일 수 있음 */
  tableLabel: string | null
  /** O14 수기 주문으로 만들어졌는지 (운영자가 직접 입력) */
  manual: boolean
}
