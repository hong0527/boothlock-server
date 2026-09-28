import type { OrderSummary } from '../types/customer'

/**
 * C1 응답에 부스 자릿세가 없던 예전 세션(배포 전에 QR을 찍어 저장된 세션)용 — 컬럼이 생기기 전 고정값이자 새 컬럼의 기본값.
 * 서버는 이 세션에도 부스 금액(기본 3,000원)을 청구하므로, 화면도 0이 아니라 이 값으로 보여 줘야 "본 금액 = 청구액"이 맞는다
 */
export const DEFAULT_SEAT_FEE_PER_PERSON = 3000

/**
 * 이번 주문과 함께 청구될 자릿세(명세서 밖, 파일럿) — 주문 확인 화면이 "손님이 본 금액 = 실제 청구액"이 되도록 미리 보여준다.
 * 서버(OrderWriter.save)는 이 세션 첫 메뉴 주문 때 "부스 1인당 금액 × 인원수" 자릿세 주문을 따로 만든다.
 * 0: 청구 안 됨(인원·금액 없음, 이미 처리됨). null: 주문내역을 못 불러와 판단할 수 없음 — 화면은 안내 문구로 대신한다.
 * seatFeeCharged는 C1이 알려 준 값 — 유휴 인계로 이어받았거나 면제된 자릿세는 C4 주문내역에 안 보여서 따로 받는다
 */
export function pendingSeatFee(
  partySize: number | undefined,
  perPerson: number | undefined,
  orders: OrderSummary[] | null,
  seatFeeCharged = false,
): number | null {
  if (!partySize || partySize <= 0 || !perPerson || perPerson <= 0 || seatFeeCharged) return 0
  if (orders === null) return null
  const charged = orders.some((o) => o.items.some((i) => i.itemType === 'SEAT_FEE'))
  return charged ? 0 : partySize * perPerson
}
