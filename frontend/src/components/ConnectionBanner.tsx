import { useSyncExternalStore } from 'react'
import { isServerUnreachable, subscribeConnection } from '../lib/connectionStatus'

/**
 * 서버와 연결이 끊겼을 때 화면 맨 위에 띄우는 띠 — 폴링 화면이 옛 목록을 최신처럼 보여주는 걸 막는다.
 * 다음 요청이 성공하면 저절로 사라진다(connectionStatus 참조).
 */
export default function ConnectionBanner() {
  const unreachable = useSyncExternalStore(subscribeConnection, isServerUnreachable, isServerUnreachable)
  if (!unreachable) return null
  return (
    <div
      role="status"
      className="fixed inset-x-0 top-0 z-[1000] bg-red-600 px-4 py-2 text-center text-body-3 text-white"
    >
      인터넷 연결이 불안정해요. 화면이 최신이 아닐 수 있어요 — 연결되면 자동으로 다시 불러와요.
    </div>
  )
}
