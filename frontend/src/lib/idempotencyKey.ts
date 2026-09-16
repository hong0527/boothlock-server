/**
 * C3 주문 생성의 Idempotency-Key를 "시도 단위"로 보관한다.
 * 실패(네트워크 끊김·5xx) 뒤 다시 누르면 같은 키를 재사용해 서버가 이미 만든 주문을 그대로 돌려주게 하고(중복 주문 방지),
 * 성공하면 폐기한다. 장바구니 내용이 바뀌면 다른 주문이므로 새 키를 쓴다.
 */
export type IdempotencyKeyStore = {
  /** 현재 시도에 쓸 키 — 없으면 새로 만든다. fingerprint(장바구니 서명)가 달라졌으면 새 키 */
  keyFor: (fingerprint: string) => string
  /** 성공 후 호출 — 다음 주문은 새 키 */
  clear: () => void
}

export function createIdempotencyKeyStore(generate: () => string = () => crypto.randomUUID()): IdempotencyKeyStore {
  let current: { key: string; fingerprint: string } | null = null
  return {
    keyFor(fingerprint) {
      if (!current || current.fingerprint !== fingerprint) {
        current = { key: generate(), fingerprint }
      }
      return current.key
    },
    clear() {
      current = null
    },
  }
}

/** 장바구니 → 서명 문자열. 같은 메뉴·수량 조합이면 순서와 무관하게 같은 값 */
export function cartFingerprint(items: { menuId: number; qty: number }[]): string {
  return [...items]
    .sort((a, b) => a.menuId - b.menuId)
    .map((i) => `${i.menuId}x${i.qty}`)
    .join(',')
}
