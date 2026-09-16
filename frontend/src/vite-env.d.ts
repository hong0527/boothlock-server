/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 서버 오리진(예: https://api.boothlock.kr). 비우면 같은 오리진 상대 경로 — .env.example 참고 */
  readonly VITE_API_BASE_URL?: string
}
