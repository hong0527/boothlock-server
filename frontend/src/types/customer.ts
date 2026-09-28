/** C1 세션 발급 응답에서 화면에 필요한 부분 (API 명세서 C1) */
export type CustomerSessionInfo = {
  boothName: string
  boothIsOpen: boolean
  tableLabel: string
  /** 테이블 이용 인원 선택 화면(PartySizePage)에서 확정 후 채워짐. 그 전까지는 없음 */
  partySize?: number
  /** C1 스캔 시점에 서버가 본 "자릿세 이미 냄"(유휴 인계로 이어받은 앞 세션 자릿세 포함). 예전에 저장된 세션엔 없다 */
  seatFeeCharged?: boolean
  /** 부스가 정한 1인당 자릿세(인원 선택 화면 안내용). 0이면 자릿세 없음. 예전에 저장된 세션엔 없다 */
  seatFeePerPerson?: number
}
/** 메뉴 분류 — 백엔드 MenuService 허용값(MAIN·SIDE·DRINK, #53)과 같다. 분류 없는 메뉴는 null */
export type MenuCategoryCode = 'MAIN' | 'SIDE' | 'DRINK'

/** C2 메뉴판의 메뉴 1건 (MenuBoardResponse.MenuItem).
 * category: 백엔드가 항상 키를 내려주며 분류 없는 메뉴는 null — 그런 메뉴는 '전체' 탭에서만 보인다('ALL' 탭은 프론트 전용).
 */
export type CustomerMenuItem = {
  id: number
  name: string
  price: number
  imageUrl?: string | null
  description?: string | null
  soldOut: boolean
  category?: MenuCategoryCode | null
}

/** global/domain/PaymentStatus — 손님 화면은 UNPAID·PAID만 구분해 보여주지만, 운영자 취소·환불 뒤 값도 그대로 내려온다 */
export type CustomerPaymentStatus = 'UNPAID' | 'PAID' | 'REFUND_NEEDED' | 'REFUNDED'

/** 장바구니에 담긴 메뉴 1건 — 주문 전까지는 로컬 상태로만 존재 */
export type CartItem = {
  menuId: number
  name: string
  unitPrice: number
  imageUrl?: string | null
  qty: number
}

export type PaymentGuide = {
  bankAccount: string
  /** 부스 설정값(예금주명) — 부스가 등록하지 않았으면 null */
  depositorName: string | null
  depositorNameRule: string
}

/** C4 내 주문 조회 응답의 주문 항목 (subtotal 없음). itemType이 'SEAT_FEE'(자릿세, 명세서 밖)면 menuId는 null */
export type OrderSummaryItem = {
  menuId: number | null
  menuName: string
  unitPrice: number
  qty: number
  itemType: 'MENU' | 'SEAT_FEE' | 'EXTRA'
}

/** C4 내 주문 조회 응답의 주문 1건. 주문내역 화면은 status·paymentStatus로 상태 뱃지를 단다(lib/customerOrderStatus) */
export type OrderSummary = {
  orderId: number
  orderNo: string
  status: 'PENDING_APPROVAL' | 'RECEIVED' | 'DONE' | 'CANCELED'
  paymentStatus: CustomerPaymentStatus
  totalAmount: number
  items: OrderSummaryItem[]
  payment: PaymentGuide
  canCancel: boolean
  createdAt: string
}