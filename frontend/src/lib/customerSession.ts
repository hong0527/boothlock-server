import type { CustomerSessionInfo } from '../types/customer'

// 명세서 §1.2: sessionToken은 쿠키 금지, 커스텀 헤더(X-Session-Token)로만 실어 보낸다.
// tableToken(QR 원본 토큰)은 교환 즉시 버리고 여기엔 저장하지 않는다.
const SESSION_TOKEN_KEY = 'boothlock_session_token'
const SESSION_INFO_KEY = 'boothlock_session_info'

export function getSessionToken(): string | null {
  return localStorage.getItem(SESSION_TOKEN_KEY)
}

export function getSessionInfo(): CustomerSessionInfo | null {
  const raw = localStorage.getItem(SESSION_INFO_KEY)
  return raw ? (JSON.parse(raw) as CustomerSessionInfo) : null
}

export function setCustomerSession(sessionToken: string, info: CustomerSessionInfo) {
  localStorage.setItem(SESSION_TOKEN_KEY, sessionToken)
  localStorage.setItem(SESSION_INFO_KEY, JSON.stringify(info))
}

export function clearCustomerSession() {
  localStorage.removeItem(SESSION_TOKEN_KEY)
  localStorage.removeItem(SESSION_INFO_KEY)
}