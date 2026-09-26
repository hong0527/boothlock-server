import { isLongWait } from './time'
import type { TableStatusInfo } from '../types/table'

/**
 * 테이블-홈 카드 배색 3단계 (Figma 672:1247 스크린샷 실측 — 빈 예시는 테두리만, 초록 예시 2건 중
 * 경과 0:35는 테두리만, 2:35는 채움이었다). 손님이 있어도(OCCUPIED) 첫 주문 후 2시간(isLongWait와
 * 같은 기준)을 넘기기 전까지는 테두리만 초록, 넘기면 채움으로 올라간다 — "자리를 오래 쓰고 있다"는
 * 신호를 배경색 자체로 준다. 예전의 "경과시간 숫자만 빨강" 규칙(기존 isLongWait 용도)은 이 단계
 * 전환으로 대체한다.
 */
export type TableTier = 'empty' | 'seated' | 'longWait'

export function tableTierOf(table: Pick<TableStatusInfo, 'status' | 'firstOrderAt'>, now: number): TableTier {
  if (table.status !== 'OCCUPIED') return 'empty'
  return isLongWait(table.firstOrderAt, now) ? 'longWait' : 'seated'
}
