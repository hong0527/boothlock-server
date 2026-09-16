/** 명세 §1.3 공통 에러 포맷 `{ error: { code, message, details? } }` — 파싱 실패(HTML 에러 페이지 등)도 방어 */
export type ApiError = {
  code?: string
  message?: string
  details?: Record<string, unknown>
}

export async function readApiError(res: Response): Promise<ApiError> {
  const body: { error?: ApiError } | null = await res.json().catch(() => null)
  return body?.error ?? {}
}
