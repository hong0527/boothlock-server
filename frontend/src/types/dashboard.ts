/** 백엔드 DashboardResponse (dashboard/dto/DashboardResponse.java)와 1:1로 맞춘 타입 */
export type OrderStatus = 'RECEIVED' | 'DONE' | 'CANCELED'

export type OrderItemSummary = {
  menuId: number
  menuName: string
  unitPrice: number
  qty: number
}

export type OrderSummary = {
  orderId: number
  orderNo: string
  status: OrderStatus
  items: OrderItemSummary[]
  createdAt: string
  /** 수기 주문(테이블 미지정)은 세션이 없어서 null일 수 있음 */
  tableLabel: string | null
}
