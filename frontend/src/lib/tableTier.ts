import type { TableStatusInfo } from '../types/table'

/**
 * 테이블-홈 카드 배색 2단계 — 빈 테이블은 테두리만, 손님이 있으면(OCCUPIED) 초록 채움.
 * 예전에는 첫 주문 후 2시간을 넘겨야 채움으로 올라가는 3단계(Figma 672:1247)였지만, 추가 자릿세는 운영자가 카드의
 * 경과시간을 보고 직접 판단한다(2026-09-28 결정) — 손님 있는 자리를 한눈에 가르는 것만 남겼다
 */
export type TableTier = 'empty' | 'occupied'

export function tableTierOf(table: Pick<TableStatusInfo, 'status'>): TableTier {
  return table.status === 'OCCUPIED' ? 'occupied' : 'empty'
}
