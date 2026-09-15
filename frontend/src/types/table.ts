/** 백엔드 O3/O22 응답(TableStatusResponse)과 1:1로 맞춘 타입 */
export type TableSessionInfo = {
  startedAt: string
  lastActivityAt: string
}

export type TableOrderItemSummary = {
  menuName: string
  qty: number
}

export type TableStatusInfo = {
  id: number
  label: string
  status: 'EMPTY' | 'OCCUPIED'
  needsCleanup: boolean
  posX: number | null
  posY: number | null
  session: TableSessionInfo | null
  unpaidOrderCount: number
  /** O3 응답엔 없음 — 프론트가 O10 대시보드 주문을 테이블별로 묶어서 계산해 붙인 값(테이블-홈 카드 표시용) */
  orderItems: TableOrderItemSummary[]
  orderTotal: number
}
