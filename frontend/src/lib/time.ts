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

/**
 * 오늘 날짜(KST, YYYY-MM-DD) — 대시보드 조회 시 businessDate로 넘겨서
 * 서버가 businessDate 생략 시 전체 날짜를 다 돌려주는 걸 피한다.
 * 주의: 실제 영업일 경계(자정~06시는 전날로 침)는 반영 안 한 근사치 — 새벽 운영 시 어긋날 수 있음
 */
export function todayKst(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())
}
