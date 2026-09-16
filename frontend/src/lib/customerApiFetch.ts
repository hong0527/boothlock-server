import { clearCustomerSession, getSessionToken } from './customerSession'

/**
 * 소비자(손님) API 호출용 공통 fetch. X-Session-Token 헤더를 자동으로 실어 보내고,
 * 세션이 없거나 410(만료·퇴실 처리)이면 세션을 지우고 재스캔 안내 화면으로 보낸다 (명세서 §1.2, C2 에러).
 */
export async function customerApiFetch(path: string, options: RequestInit = {}): Promise<Response> {
  const token = getSessionToken()
  if (!token) {
    clearCustomerSession()
    window.location.href = '/session-expired'
    throw new Error('세션이 없어요.')
  }

  const headers = new Headers(options.headers)
  headers.set('X-Session-Token', token)

  const res = await fetch(path, { ...options, headers })

  if (res.status === 410) {
    clearCustomerSession()
    window.location.href = '/session-expired'
    throw new Error('세션이 만료됐어요.')
  }

  return res
}