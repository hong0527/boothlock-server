export type MenuItem = {
  id: number
  name: string
  price: number
  soldOut: boolean
  imageUrl?: string | null
  visible: boolean
  /** ETC = 기타 항목(추가 자릿세·쿠폰 등, 운영자 전용) — 손님 메뉴판에 안 나오고 음수(할인) 가격일 수 있다 */
  category?: 'MAIN' | 'SIDE' | 'DRINK' | 'ETC' | null
}
