/** 백엔드 O3/O22 응답(TableStatusResponse)과 1:1로 맞춘 타입 */
export type TableSessionInfo = {
  startedAt: string
  lastActivityAt: string
  /** 세션 PK — O10 OrderSummary.sessionId와 맞춰 지금 앉은 손님 주문을 고른다 */
  id: number
}

export type TableOrderItemSummary = {
  menuName: string
  qty: number
}

/**
 * O6 퇴실 응답 (tableqr/dto/TableCheckoutResponse.java).
 * 기존 구현은 unpaidWarning(boolean)만 주고, 테이블 갈래 개정 후에는 명세 O6의 id·label·status·warning(미결제 있을 때만)이 더해진다.
 * 어느 쪽이 와도 읽을 수 있게 새 필드는 전부 선택으로 둔다.
 */
export type TableCheckoutResult = {
  unpaidWarning: boolean
  id?: number
  label?: string
  status?: 'EMPTY' | 'OCCUPIED'
  warning?: string
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
