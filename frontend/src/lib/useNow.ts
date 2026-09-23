import { useEffect, useState } from 'react'

/** intervalMs 간격으로 갱신되는 현재 시각(ms) — 상단 상시 시계 등 실시간 표시에 쓴다 */
export function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(id)
  }, [intervalMs])

  return now
}
