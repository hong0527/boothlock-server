/** C1 세션 발급 응답에서 화면에 필요한 부분 (API 명세서 C1) */
export type CustomerSessionInfo = {
  boothName: string
  boothIsOpen: boolean
  tableLabel: string
}
/** C2 메뉴판의 메뉴 1건.
 * category: 목업(전체/메인메뉴/사이드/음료 탭)엔 있지만 현재 백엔드 C2 응답엔 없는 필드 —
 * 값이 없으면(undefined) '전체' 탭에서만 보인다. 백엔드에 필드가 추가되면 그대로 동작한다.
 */
export type CustomerMenuItem = {
  id: number
  name: string
  price: number
  imageUrl?: string | null
  description?: string | null
  soldOut: boolean
  category?: 'MAIN' | 'SIDE' | 'DRINK'
}

/** 장바구니에 담긴 메뉴 1건 — 주문 전까지는 로컬 상태로만 존재 */
export type CartItem = {
  menuId: number
  name: string
  unitPrice: number
  qty: number
}

export type PaymentGuide = {
  bankAccount: string
  depositorNameRule: string
}

/** C3 주문 생성 응답의 주문 항목 (subtotal 있음) */
export type OrderCreateItem = {
  menuId: number
  menuName: string
  unitPrice: number
  qty: number
  subtotal: number
}

/** C3 주문 생성 응답 */
export type OrderCreateResult = {
  orderId: number
  orderNo: string
  status: 'RECEIVED' | 'DONE' | 'CANCELED'
  paymentStatus: 'UNPAID' | 'PAID'
  totalAmount: number
  items: OrderCreateItem[]
  payment: PaymentGuide
  createdAt: string
}

/** C4 내 주문 조회 응답의 주문 항목 (subtotal 없음) */
export type OrderSummaryItem = {
  menuId: number
  menuName: string
  unitPrice: number
  qty: number
}

/** C4 내 주문 조회 응답의 주문 1건 */
export type OrderSummary = {
  orderId: number
  orderNo: string
  status: 'RECEIVED' | 'DONE' | 'CANCELED'
  paymentStatus: 'UNPAID' | 'PAID'
  totalAmount: number
  items: OrderSummaryItem[]
  payment: PaymentGuide
  canCancel: boolean
  createdAt: string
}