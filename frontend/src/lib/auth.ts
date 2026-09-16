export type StaffInfo = {
  role: 'SUPER_ADMIN' | 'ADMIN' | 'STAFF'
  boothId: number | null
  boothName: string | null
}

const TOKEN_KEY = 'boothlock_token'
const STAFF_KEY = 'boothlock_staff'

export function getAuthToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getStaff(): StaffInfo | null {
  const raw = localStorage.getItem(STAFF_KEY)
  return raw ? (JSON.parse(raw) as StaffInfo) : null
}

export function setAuth(token: string, staff: StaffInfo) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(STAFF_KEY, JSON.stringify(staff))
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(STAFF_KEY)
}
