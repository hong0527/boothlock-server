/** 공개 축(인증 없음) E1·E2 — 백엔드 event/dto/BoothListResponse.java·EventMapResponse.java와 1:1 */

/** booth/domain/BoothCategory.java — 분류 없는 부스는 null */
export type BoothCategory = 'FOOD' | 'CAFE' | 'GOODS' | 'ETC'

export type EventBooth = {
  boothId: number
  name: string
  category: BoothCategory | null
  /** 주문 접수 스위치(O17). false는 "영업 종료"가 아니라 "주문 마감" (명세 E1) */
  isOpen: boolean
  /** 약도 기준 상대 좌표 0~10000 (= 0.00%~100.00%). 미설정이면 null → 지도에 핀을 찍지 않고 목록에만 */
  mapX: number | null
  mapY: number | null
  /** 서버는 숫자만 준다 — 여유·보통·만석 변환은 프론트(lib/eventMap.ts) */
  tables: { total: number; empty: number }
}

export type BoothListResponse = { booths: EventBooth[] }

export type EventMapResponse = {
  /** 서버 상대 주소(/uploads/event/map.png) — 표시할 때 assetUrl로 베이스를 붙인다 */
  imageUrl: string
  width: number
  height: number
  updatedAt: string
}

export const BOOTH_CATEGORY_LABEL: Record<BoothCategory, string> = {
  FOOD: '음식',
  CAFE: '카페',
  GOODS: '굿즈',
  ETC: '기타',
}
