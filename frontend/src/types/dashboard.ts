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
  /**
   * TODO: 백엔드 OrderSummary에 아직 없는 필드 — DB(orders.table_label)엔 있는데 매퍼에서 응답에 안 담고 있음.
   * 필드 추가되면 이 optional을 없애고 실제 값을 그대로 씀.
   */
  tableLabel?: string | null
}
