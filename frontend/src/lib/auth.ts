import { readStored, readStoredJson, removeStored, writeStored } from './safeStorage'

export type StaffInfo = {
  role: 'SUPER_ADMIN' | 'ADMIN' | 'STAFF'
  boothId: number | null
  boothName: string | null
}

const TOKEN_KEY = 'boothlock_token'
const STAFF_KEY = 'boothlock_staff'

// 손님 세션과 같은 이유로 safeStorage를 거친다 — 운영자 노트북이라 저장이 막힐 일은 드물지만,
// 깨진 JSON이 남으면 getStaff()가 렌더 중에 던져 화면이 통째로 백지가 되는 건 여기도 같다.
export function getAuthToken(): string | null {
  return readStored(TOKEN_KEY)
}

export function getStaff(): StaffInfo | null {
  return readStoredJson<StaffInfo>(STAFF_KEY)
}

export function setAuth(token: string, staff: StaffInfo) {
  writeStored(TOKEN_KEY, token)
  writeStored(STAFF_KEY, JSON.stringify(staff))
}

/**
 * 저장된 부스명만 갱신한다 — 토큰은 건드리지 않는다.
 *
 * 지금 이 값을 화면에 그리는 운영자 화면은 없다(손님 화면이 보는 부스명은 별도 경로다).
 * 그래도 갱신하는 이유는, 로그인 때 받은 값이 그대로 굳어 있어서 표시처가 생기는 순간
 * 옛 이름이 드러나기 때문이다. 어긋난 값을 남겨두지 않는다.
 *
 * 로그인하지 않은 상태면 아무것도 하지 않는다.
 */
export function updateStoredBoothName(boothName: string) {
  const staff = getStaff()
  if (!staff) return
  // 반드시 safeStorage를 거친다 — 저장을 조용히 무시하는 브라우저에서는 getStaff가
  // 메모리 대체본을 읽으므로, localStorage에 직접 쓰면 읽는 쪽과 영원히 어긋난다.
  writeStored(STAFF_KEY, JSON.stringify({ ...staff, boothName }))
}

export function clearAuth() {
  removeStored(TOKEN_KEY)
  removeStored(STAFF_KEY)
}
