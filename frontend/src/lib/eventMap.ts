/** 홈 화면(E1·E2) 표시 계산 — 서버는 숫자만 주고 3단계 변환·좌표 환산은 프론트가 한다 (명세 E1 "임계값을 서버에 박지 않는 이유") */

export type CrowdLevel = '여유' | '보통' | '만석'

/** 명세 E1 권장 임계값: empty/total ≥ 0.5 → 여유, > 0 → 보통, = 0 → 만석. 행사 중 체감과 어긋나면 여기만 고친다 */
export function crowdLevel(total: number, empty: number): CrowdLevel {
  if (total <= 0 || empty <= 0) return '만석'
  return empty / total >= 0.5 ? '여유' : '보통'
}

/** 카드 둘째 줄 문구. 주문 마감(isOpen=false)이 좌석 수보다 먼저다 — 자리가 있어도 주문을 못 하면 헛걸음 */
export function seatDescription(total: number, empty: number, isOpen: boolean): string {
  if (!isOpen) return '주문 마감'
  if (total <= 0) return '테이블 정보 없음'
  if (empty <= 0) return '남은 테이블 없음'
  return `테이블 ${empty}개 남음`
}

export type Rect = { left: number; top: number; width: number; height: number }

/**
 * object-fit: contain 으로 그려진 이미지가 컨테이너 안에서 실제로 차지하는 영역.
 * 핀 좌표는 이미지 기준 비율이라, 여백(레터박스)을 빼고 이 영역 안에서 환산해야 이미지와 어긋나지 않는다
 */
export function containRect(containerW: number, containerH: number, imageW: number, imageH: number): Rect {
  if (containerW <= 0 || containerH <= 0 || imageW <= 0 || imageH <= 0) {
    return { left: 0, top: 0, width: 0, height: 0 }
  }
  const scale = Math.min(containerW / imageW, containerH / imageH)
  const width = imageW * scale
  const height = imageH * scale
  return { left: (containerW - width) / 2, top: (containerH - height) / 2, width, height }
}

export const MAP_COORD_MAX = 10000

/** mapX·mapY(0~10000) → 컨테이너 픽셀. 명세 E2: pixelX = mapX / 10000 * 표시폭 */
export function pinPosition(mapX: number, mapY: number, rect: Rect): { x: number; y: number } {
  const clamp = (v: number) => Math.min(MAP_COORD_MAX, Math.max(0, v))
  return {
    x: rect.left + (clamp(mapX) / MAP_COORD_MAX) * rect.width,
    y: rect.top + (clamp(mapY) / MAP_COORD_MAX) * rect.height,
  }
}
