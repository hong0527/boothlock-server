const TOKEN_KEY = 'boothlock_token'

/**
 * TODO: 로그인 API(POST /admin/auth/login) 연동 시 응답의 accessToken을
 * localStorage.setItem(TOKEN_KEY, accessToken)으로 저장하도록 LoginPage에서 채워야 함.
 * 그 전까지는 여기서 항상 null을 반환하고, 이걸 쓰는 화면은 401/인증 실패로 뜬다.
 */
export function getAuthToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
