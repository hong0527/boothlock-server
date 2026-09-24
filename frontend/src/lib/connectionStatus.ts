/**
 * 서버 연결 상태 — 화면 상단 끊김 배너(ConnectionBanner)가 구독한다.
 *
 * 폴링 화면은 요청이 실패해도 마지막으로 받은 목록을 그대로 보여준다. 축제장 인터넷이 끊기면 운영자는
 * 멈춘 목록을 최신인 줄 알고 보게 된다. 그래서 "지금 서버와 안 닿는다"는 사실을 따로 알린다.
 * 판단 기준은 실제 요청 결과다(fetchWithTimeout이 기록). 응답이 오면 연결됨이고, 네트워크 오류·시간 초과·
 * 게이트웨이 오류(502·504 — nginx는 떠 있는데 api가 안 닿음)면 끊김이다.
 * 4xx·500·503은 서버와는 닿은 것이라 연결됨으로 본다. 503은 api가 직접 내는 "사진 처리 중(UPLOAD_BUSY)"이라
 * 끊김으로 보면 사진을 올릴 때마다 배너가 잘못 뜬다(우리 nginx는 503을 내지 않는다).
 */
type Listener = () => void

let unreachable = false
const listeners = new Set<Listener>()

function set(next: boolean) {
  if (unreachable === next) return
  unreachable = next
  listeners.forEach((l) => l())
}

export function markReachable() {
  set(false)
}

export function markUnreachable() {
  set(true)
}

export function isGatewayFailure(status: number) {
  return status === 502 || status === 504
}

export function subscribeConnection(listener: Listener) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function isServerUnreachable() {
  return unreachable
}
