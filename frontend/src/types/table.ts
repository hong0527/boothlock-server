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
  /** 이 퇴실로 완료(DONE) 처리된 남은 접수 주문 수 */
  completedOrderCount?: number
  /** 이 퇴실("테이블 비우기")로 자동 거절된 승인대기(O28) 주문 수(v0.6.12). 입금된 건은 '환불필요'로 넘어간다 */
  rejectedPendingCount?: number
  warning?: string
}

export type TableStatusInfo = {
  id: number
  label: string
  status: 'EMPTY' | 'OCCUPIED'
  needsCleanup: boolean
  posX: number | null
  posY: number | null
  /** 파일럿용 그리드 좌표(O22b) — 운영자가 숫자로 직접 입력하는 행/열. posX/posY(드래그, 파일럿 이후 재사용 예정)와는 별개 */
  gridRow: number | null
  gridCol: number | null
  session: TableSessionInfo | null
  unpaidOrderCount: number
  /** O3 응답엔 없음 — 프론트가 O10 대시보드 주문을 테이블별로 묶어서 계산해 붙인 값(테이블-홈 카드 표시용) */
  orderItems: TableOrderItemSummary[]
  orderTotal: number
  /** 지금 앉은 손님의 첫 주문 시각(취소 제외) — 테이블-홈 카드 시간 표시용. 주문이 없으면 null. orderItems와 같이 프론트가 계산 */
  firstOrderAt: string | null
  /** 지금 앉은 손님의 승인대기(O28) 주문 수 — orderTotal에서는 빠진다(결제 모달과 같은 기준). 카드에 "승인대기 N"으로 표시. 프론트 계산 */
  pendingApprovalCount: number
}
