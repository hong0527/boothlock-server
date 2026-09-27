import type { OrderSummary } from '../types/customer'

/** 자릿세(명세서 밖, 파일럿) — 1인당 금액. 서버 OrderWriter.SEAT_FEE_PER_PERSON과 같아야 한다 */
export const SEAT_FEE_PER_PERSON = 3000

/**
 * 이번 주문에 붙을 자릿세 — 주문 확인 화면에 "손님이 동의한 금액 = 실제 청구액"이 되도록 미리 보여준다.
 * 서버 규칙과 같다: 이 세션에 취소 안 된 주문의 자릿세가 아직 없으면 인원수 × 3,000원, 있으면 0.
 * orders가 null(주문내역을 못 불러옴)이면 판단할 수 없으니 null — 화면은 "첫 주문에 자릿세가 함께 청구돼요" 안내로 대신한다.
 * seatFeeCharged는 C1이 알려 준 값(CustomerSessionInfo) — 유휴 인계로 앞 세션 자릿세를 이어받은 세션은 C4 주문내역에
 * 자릿세가 없어도 서버가 다시 받지 않는다. 그 경우 orders만 보면 받지 않을 금액을 보여 주게 된다
 */
export function pendingSeatFee(
  partySize: number | undefined,
  orders: OrderSummary[] | null,
  seatFeeCharged = false,
): number | null {
  if (!partySize || partySize <= 0) return 0
  if (seatFeeCharged) return 0
  if (orders === null) return null
  const charged = orders.some((o) => o.status !== 'CANCELED' && o.items.some((i) => i.itemType === 'SEAT_FEE'))
  return charged ? 0 : partySize * SEAT_FEE_PER_PERSON
}
