import type { CustomerSessionInfo } from '../types/customer'
import { readStored, readStoredJson, removeStored, writeStored } from './safeStorage'

// 명세서 §1.2: sessionToken은 쿠키 금지, 커스텀 헤더(X-Session-Token)로만 실어 보낸다.
// tableToken(QR 원본 토큰)은 교환 즉시 버리고 여기엔 저장하지 않는다.
//
// 저장은 safeStorage를 거친다 — 손님 폰이 사이트 데이터를 막고 있으면 localStorage가 예외 없이
// 무시돼 방금 발급받은 세션을 잃고 "세션 만료" 화면에 갇힌다(iOS Safari 실측). 사유는 safeStorage 주석 참조.
const SESSION_TOKEN_KEY = 'boothlock_session_token'
const SESSION_INFO_KEY = 'boothlock_session_info'

export function getSessionToken(): string | null {
  return readStored(SESSION_TOKEN_KEY)
}

export function getSessionInfo(): CustomerSessionInfo | null {
  return readStoredJson<CustomerSessionInfo>(SESSION_INFO_KEY)
}

export function setCustomerSession(sessionToken: string, info: CustomerSessionInfo) {
  writeStored(SESSION_TOKEN_KEY, sessionToken)
  writeStored(SESSION_INFO_KEY, JSON.stringify(info))
}

export function clearCustomerSession() {
  removeStored(SESSION_TOKEN_KEY)
  removeStored(SESSION_INFO_KEY)
}

export function setSessionPartySize(partySize: number) {
  const token = getSessionToken()
  const info = getSessionInfo()
  if (!token || !info) return
  setCustomerSession(token, { ...info, partySize })
}