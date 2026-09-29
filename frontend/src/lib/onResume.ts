/**
 * 화면으로 돌아왔거나(탭 전환·폰 잠금 해제) 인터넷이 다시 연결됐을 때 바로 한 번 부른다.
 *
 * 폰은 잠기거나 백그라운드로 가면 setInterval을 멈추거나 크게 늦춘다. 운영자가 폰을 다시 켜면
 * 다음 폴링 주기가 올 때까지 옛 주문 목록이 보이고, 끊겼다 붙은 직후에도 마찬가지다.
 * 폴링 효과(effect)에서 setInterval과 함께 등록하고, 돌려받은 함수로 정리한다.
 * 왜 불렸는지(화면 복귀 'visible' / 재연결 'online')를 넘긴다 — 대부분은 무시하고, 직원 알림 감시만 구분해 쓴다.
 */
export type ResumeReason = 'visible' | 'online'

export function onResume(callback: (reason: ResumeReason) => void): () => void {
  if (typeof window === 'undefined' || typeof document === 'undefined') return () => {}
  const onVisible = () => {
    if (document.visibilityState === 'visible') callback('visible')
  }
  const onOnline = () => callback('online')
  document.addEventListener('visibilitychange', onVisible)
  window.addEventListener('online', onOnline)
  return () => {
    document.removeEventListener('visibilitychange', onVisible)
    window.removeEventListener('online', onOnline)
  }
}
