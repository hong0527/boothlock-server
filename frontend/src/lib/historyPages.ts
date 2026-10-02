/** 완료·취소 탭 한 페이지 크기 — 백엔드 DashboardQueryService.HISTORY_TAB_LIMIT과 같아야 "더 있음"을 맞게 판정한다 */
export const HISTORY_PAGE_SIZE = 100

/** O10 조회 경로 — 완료·취소 탭 커서(beforeOrderId)는 DashboardQueryService 주석 참고 */
export function dashboardPath(
  status: string,
  { openSessionsOnly = false, beforeOrderId }: { openSessionsOnly?: boolean; beforeOrderId?: number } = {},
): string {
  const params = new URLSearchParams({ status })
  if (openSessionsOnly) params.set('activeSessionOnly', 'true')
  if (beforeOrderId !== undefined) params.set('beforeOrderId', String(beforeOrderId))
  return `/api/v1/admin/orders?${params}`
}
