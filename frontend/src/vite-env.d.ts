/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 서버 오리진(예: https://api.boothlock.kr). 비우면 같은 오리진 상대 경로 — .env.example 참고 */
  readonly VITE_API_BASE_URL?: string
  /** 회원가입 화면(/signup, 임시 데모) 노출 여부. 문자열 'true'일 때만 켜짐 — lib/featureFlags.ts 참고 */
  readonly VITE_SIGNUP_ENABLED?: string
}
