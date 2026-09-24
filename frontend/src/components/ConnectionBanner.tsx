import { useEffect, useSyncExternalStore } from 'react'
import { apiUrl } from '../lib/apiBase'
import { isServerUnreachable, subscribeConnection } from '../lib/connectionStatus'
import { fetchWithTimeout } from '../lib/fetchWithTimeout'
import { onResume } from '../lib/onResume'

/** 끊긴 동안 연결 복구를 확인하는 간격 */
const PROBE_INTERVAL_MS = 15_000

/**
 * 서버와 연결이 끊겼을 때 화면 맨 위에 띄우는 띠 — 폴링 화면이 옛 목록을 최신처럼 보여주는 걸 막는다.
 * 다음 요청이 성공하면 사라진다(connectionStatus 참조).
 *
 * <p>장바구니·주문 확인처럼 폴링이 없는 화면에서는 "다음 요청"이 안 생겨 망이 돌아와도 띠가 남는다.
 * 그래서 띠가 떠 있는 동안에만 15초마다·화면 복귀·online 때 가벼운 공개 조회(E1)로 복구를 확인한다.
 * fixed가 아니라 sticky로 둔다 — 화면 위를 덮으면 끊겼을 때 정작 뒤로가기·탭을 못 누른다.
 */
export default function ConnectionBanner() {
  const unreachable = useSyncExternalStore(subscribeConnection, isServerUnreachable, isServerUnreachable)

  useEffect(() => {
    if (!unreachable) return
    const probe = () => {
      fetchWithTimeout(apiUrl('/api/v1/event/booths')).catch(() => {})
    }
    const id = setInterval(probe, PROBE_INTERVAL_MS)
    const offResume = onResume(probe)
    return () => {
      clearInterval(id)
      offResume()
    }
  }, [unreachable])

  if (!unreachable) return null
  return (
    <div role="status" className="sticky top-0 z-[1000] bg-red-600 px-4 py-2 text-center text-body-3 text-white">
      인터넷 연결이 불안정해요. 화면이 최신이 아닐 수 있어요 — 연결되면 이 표시가 사라져요.
    </div>
  )
}
