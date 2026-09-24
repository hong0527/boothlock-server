import { apiUrl } from './apiBase'
import { fetchWithTimeout } from './fetchWithTimeout'
import { clearAuth, getAuthToken } from './auth'

/**
 * 인증 필요한 API 호출용 공통 fetch. 토큰을 자동으로 Authorization 헤더에 실어 보내고,
 * 토큰이 없거나(로그인 안 함) 401(토큰 만료 등)이면 로그아웃 처리 후 로그인 화면으로 보낸다.
 */
export async function apiFetch(path: string, options: RequestInit = {}): Promise<Response> {
  // 라우터 밖(일반 함수)이라 useNavigate를 못 씀 — 어차피 인증 만료는 전체 상태(Context 등)를
  // 깨끗이 리셋하는 게 맞는 상황이라 전체 새로고침을 의도적으로 씀 (SPA 네비게이션 아님)
  const token = getAuthToken()
  if (!token) {
    clearAuth()
    window.location.href = '/'
    throw new Error('로그인이 필요해요.')
  }

  // Headers 인스턴스·튜플 배열로 온 헤더도 안전하게 병합 (스프레드는 Headers 이터레이터를 안 돌려서 값이 사라짐)
  const headers = new Headers(options.headers)
  headers.set('Authorization', `Bearer ${token}`)

  const res = await fetchWithTimeout(apiUrl(path), { ...options, headers })

  if (res.status === 401) {
    clearAuth()
    window.location.href = '/'
    throw new Error('로그인이 만료됐어요.')
  }

  return res
}
