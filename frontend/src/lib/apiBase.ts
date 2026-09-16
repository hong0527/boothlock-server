/**
 * API 서버 주소 한 곳 모음 — 프론트와 API를 다른 도메인에 배포할 때 `VITE_API_BASE_URL`만 바꾸면 되게 한다.
 * 비어 있으면(기본) 같은 오리진 상대 경로 → 개발 환경에서는 vite 프록시(`/api`, `/uploads`)가 백엔드로 넘긴다.
 */
export const API_BASE_URL = normalizeBase(import.meta.env.VITE_API_BASE_URL)

export function normalizeBase(raw: unknown): string {
  return typeof raw === 'string' ? raw.trim().replace(/\/+$/, '') : ''
}

/** `/api/v1/...` 같은 상대 경로 앞에 베이스를 붙인다. 이미 절대 URL이면 그대로 */
export function apiUrl(path: string, base: string = API_BASE_URL): string {
  if (/^https?:\/\//i.test(path)) return path
  if (!base) return path
  return `${base}${path.startsWith('/') ? path : `/${path}`}`
}

/**
 * 응답에 들어오는 이미지 주소(`/uploads/menu/…`, E2 `imageUrl`)를 표시용 절대 주소로.
 * 절대 URL·blob:·data: 는 그대로 두고, `/`로 시작하는 상대 주소에만 베이스를 붙인다.
 */
export function assetUrl(url: string | null | undefined, base: string = API_BASE_URL): string | undefined {
  if (!url) return undefined
  if (/^(https?:\/\/|blob:|data:)/i.test(url)) return url
  return apiUrl(url, base)
}
