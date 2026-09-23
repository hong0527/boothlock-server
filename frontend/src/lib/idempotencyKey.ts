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

// crypto.randomUUID()는 보안 컨텍스트(HTTPS·localhost)에서만 동작한다 — 지금 배포는 평문 HTTP라
// 실제 기기(HTTPS 아님)에서 호출 즉시 TypeError가 나서 fetch 이전에 조용히 실패했다(실측).
// crypto.getRandomValues는 보안 컨텍스트 제한이 없어 HTTP에서도 동작한다.
function generateRandomId(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

/**
 * 재시도로 볼 수 있는 간격 — 마지막으로 키를 쓴 뒤 이보다 오래 지나면 새 주문으로 보고 새 키를 낸다.
 * 서버는 같은 키를 기한 없이 기존 주문으로 돌려준다. 그래서 "응답 유실 → 재시도 안 하고 주문내역 확인 →
 * 20분 뒤 같은 메뉴를 또 주문"하면 옛 키가 나가 서버가 첫 주문을 돌려주고, 두 번째 주문은 아무도 모르게 사라졌다.
 * 응답을 못 받은 뒤 다시 누르는 건 대개 수십 초 안이다(요청 제한시간 15초).
 */
export const RETRY_WINDOW_MS = 3 * 60_000

export function createIdempotencyKeyStore(
  generate: () => string = generateRandomId,
  now: () => number = Date.now,
): IdempotencyKeyStore {
  let current: { key: string; fingerprint: string; lastUsedAt: number } | null = null
  return {
    keyFor(fingerprint) {
      const t = now()
      if (!current || current.fingerprint !== fingerprint || t - current.lastUsedAt > RETRY_WINDOW_MS) {
        current = { key: generate(), fingerprint, lastUsedAt: t }
      }
      current.lastUsedAt = t
      return current.key
    },
    clear() {
      current = null
    },
  }
}

/**
 * 손님 주문(C3) 키 저장소 — 모듈에 하나만 둔다.
 * 화면(OrderConfirmPage) 안의 useRef에 두면 "응답 유실 → 뒤로 가기 → 다시 주문 확인"에서 화면이 새로 떠 키가 새로 생기고,
 * 서버는 이미 만든 주문과 별개로 받아 중복 주문이 됐다. 모듈 단위면 탭이 살아 있는 동안 같은 장바구니는 같은 키다.
 * 서명에 세션 토큰을 섞어 다른 세션(퇴실 후 재스캔)에서는 새 키가 나오게 한다.
 */
export const customerOrderKeys = createIdempotencyKeyStore()

/** 손님 주문 키의 서명 — 세션 토큰 + 장바구니 */
export function customerOrderFingerprint(sessionToken: string | null, items: { menuId: number; qty: number }[]): string {
  return `${sessionToken ?? ''}|${cartFingerprint(items)}`
}

/** 장바구니 → 서명 문자열. 같은 메뉴·수량 조합이면 순서와 무관하게 같은 값 */
export function cartFingerprint(items: { menuId: number; qty: number }[]): string {
  return [...items]
    .sort((a, b) => a.menuId - b.menuId)
    .map((i) => `${i.menuId}x${i.qty}`)
    .join(',')
}
