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

export function clearAuth() {
  removeStored(TOKEN_KEY)
  removeStored(STAFF_KEY)
}
