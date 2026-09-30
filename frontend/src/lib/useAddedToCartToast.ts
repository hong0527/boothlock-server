import { useCallback, useEffect, useRef, useState } from 'react'

/** 담은 직후 얼마나 보여줄지 — 너무 짧으면 못 보고, 너무 길면 다음 메뉴를 담을 때 겹쳐 헷갈린다 */
const VISIBLE_MS = 1400
/** 사라질 때 튀지 않게 잠깐 페이드아웃하는 시간(AddedToCartToast의 transition-duration과 맞춘다) */
const FADE_MS = 200

export type AddedToCartEntry = { message: string; fading: boolean }

/**
 * 메뉴판(C2)에서 "담기"를 눌렀을 때의 확인 표시 상태 — 장바구니 배지 숫자만으로는
 * 육안으로 담겼는지 확인하기 어렵다는 피드백(2026-09-30)으로 추가했다.
 * 화면 하나에 토스트 한 개만 쓴다는 전제로 간단히 뒀다 — 여러 개를 쌓아 보여줘야 하면 배열로 바꿀 것.
 */
export function useAddedToCartToast() {
  const [entry, setEntry] = useState<AddedToCartEntry | null>(null)
  const hideTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const clearTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const show = useCallback((message: string) => {
    // 연달아 담으면(같은 메뉴 연타·다른 메뉴 바로 이어 담기) 이전 타이머를 버리고 새로 센다 —
    // 안 그러면 먼저 예약된 숨김이 방금 보여준 메시지를 조기에 지운다
    clearTimeout(hideTimer.current)
    clearTimeout(clearTimer.current)
    setEntry({ message, fading: false })
    hideTimer.current = setTimeout(() => {
      setEntry((prev) => (prev ? { ...prev, fading: true } : prev))
      clearTimer.current = setTimeout(() => setEntry(null), FADE_MS)
    }, VISIBLE_MS)
  }, [])

  useEffect(
    () => () => {
      clearTimeout(hideTimer.current)
      clearTimeout(clearTimer.current)
    },
    [],
  )

  return { entry, show }
}
