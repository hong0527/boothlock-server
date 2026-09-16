export function formatElapsed(createdAt: string, now: number): string {
  const minutes = Math.max(0, Math.floor((now - new Date(createdAt).getTime()) / 60000))
  if (minutes < 1) return '방금 전'
  if (minutes < 60) return `${minutes}분 전`
  return `${Math.floor(minutes / 60)}시간 전`
}

export function formatClockTime(createdAt: string): string {
  const date = new Date(createdAt)
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${hours}:${minutes}`
}

/** 영업일 경계 — 백엔드 OrderNumberingService.businessDateOf와 같은 06:00 KST (명세 §1.1) */
export const BUSINESS_DAY_START_HOUR = 6

/**
 * 영업일(KST, YYYY-MM-DD) — 06:00 이전은 전날 영업일로 친다.
 * 대시보드 조회(O10)는 businessDate를 생략하면 서버가 이 값을 기본으로 쓰므로 평소엔 보낼 필요가 없다.
 * 특정 영업일을 명시해 조회할 때(전일 환불 처리 등)만 이 함수로 만든 값을 넘긴다.
 */
export function businessDateKst(now: Date = new Date()): string {
  const shifted = new Date(now.getTime() - BUSINESS_DAY_START_HOUR * 60 * 60 * 1000)
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(shifted)
}
