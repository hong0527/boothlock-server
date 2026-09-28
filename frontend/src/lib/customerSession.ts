import type { CustomerSessionInfo } from '../types/customer'
import { readStored, readStoredJson, removeStored, writeStored } from './safeStorage'
import { customerOrderKeys } from './idempotencyKey'

// 명세서 §1.2: sessionToken은 쿠키 금지, 커스텀 헤더(X-Session-Token)로만 실어 보낸다.
// tableToken(QR 원본 토큰)은 교환 즉시 버리고 여기엔 저장하지 않는다.
//
// 저장은 safeStorage를 거친다 — 손님 폰이 사이트 데이터를 막고 있으면 localStorage가 예외 없이
// 무시돼 방금 발급받은 세션을 잃고 "세션 만료" 화면에 갇힌다(iOS Safari 실측). 사유는 safeStorage 주석 참조.
const SESSION_TOKEN_KEY = 'boothlock_session_token'
const SESSION_INFO_KEY = 'boothlock_session_info'
// 장바구니(CartContext)도 여기서 같이 관리한다 — 세션이 바뀌거나 끝날 때 앞 손님 장바구니가 다음 손님에게
// 넘어가면 안 되기 때문에, 저장 키 하나로 묶어 이 파일이 세션·장바구니 수명을 함께 관리한다
export const CART_STORAGE_KEY = 'boothlock_cart_items'

export function getSessionToken(): string | null {
  return readStored(SESSION_TOKEN_KEY)
}

export function getSessionInfo(): CustomerSessionInfo | null {
  return readStoredJson<CustomerSessionInfo>(SESSION_INFO_KEY)
}

/**
 * 백그라운드로 밀렸다가 브라우저가 탭을 통째로 새로고침하는 경우(안드로이드 크롬 실측 — QR을 카메라로
 * 찍고 앱을 오가는 사이 자주 걸린다), 메뉴판에서 눌러 담아둔 장바구니는 React 상태라 그대로 날아간다.
 * 세션 토큰은 저장소에 남아 있어 다시 들어와도, 손님은 담았던 걸 처음부터 다시 눌러야 했다 — 그래서
 * 장바구니도 저장소에 같이 남긴다(CartContext가 읽고 쓴다). 세션이 그대로면 장바구니도 그대로 살아난다.
 */
export function setCustomerSession(sessionToken: string, info: CustomerSessionInfo) {
  // 토큰이 실제로 바뀌면(유휴 만료 후 재스캔·퇴실 후 새 세션 등) 진짜 새 손님이다 — 앞 손님 장바구니를 넘기지 않는다.
  // 복원(같은 토큰 그대로 돌아옴)이면 장바구니를 건드리지 않는다
  const previousToken = getSessionToken()
  if (previousToken !== null && previousToken !== sessionToken) {
    removeStored(CART_STORAGE_KEY)
  }
  writeStored(SESSION_TOKEN_KEY, sessionToken)
  writeStored(SESSION_INFO_KEY, JSON.stringify(info))
}

export function clearCustomerSession() {
  // 세션이 끝나면 진행 중이던 주문 키·장바구니도 버린다 — 다음 세션 주문이 이전 세션 키로 나가 400을 받거나,
  // 다음 손님 화면에 앞 손님이 담아둔 메뉴가 보이지 않게
  customerOrderKeys.clear()
  removeStored(SESSION_TOKEN_KEY)
  removeStored(SESSION_INFO_KEY)
  removeStored(CART_STORAGE_KEY)
}

export function setSessionPartySize(partySize: number) {
  const token = getSessionToken()
  const info = getSessionInfo()
  if (!token || !info) return
  setCustomerSession(token, { ...info, partySize })
}