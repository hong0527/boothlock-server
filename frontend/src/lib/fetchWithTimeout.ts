/**
 * 제한 시간이 있는 fetch.
 *
 * 축제 현장은 200~300명이 모이면 인터넷이 잘 안 터진다(실제 제보). 약한 신호에서는 TCP 재전송 때문에
 * 요청 하나가 수 분씩 매달린다. 그동안 pollGuard가 "앞 요청이 아직 돈다"며 다음 폴링을 전부 건너뛰고,
 * 오류도 안 나서 운영자 화면이 아무 표시 없이 옛 주문 목록에 굳었다. 버튼도 같이 잠겼다.
 * 제한 시간을 두면 매달린 요청이 실패로 끝나고, 화면은 오류를 보여준 뒤 다음 폴링에서 회복한다.
 *
 * 기본값: 조회(GET) 10초, 변경 15초, 파일 업로드(FormData) 120초. 필요한 곳은 timeoutMs로 따로 준다.
 * 제한 시간은 본문을 다 받을 때까지 잰다. 헤더까지만 재면, 헤더는 왔는데 본문 패킷이 재전송에 걸린 경우
 * res.json()이 한없이 기다려 폴링이 다시 굳는다(이미 "응답 옴"으로 기록돼 끊김 배너도 안 뜬다).
 * 그래서 본문을 여기서 끝까지 받아 새 Response에 담아 돌려준다 — 이 앱의 응답은 작은 JSON이라 버퍼링해도 된다.
 * AbortSignal.timeout은 iOS 15에 없어서 쓰지 않는다.
 *
 * 결과는 connectionStatus에 기록해 끊김 배너가 뜨고 꺼지게 한다.
 */
import { isGatewayFailure, markReachable, markUnreachable } from './connectionStatus'

export class TimeoutError extends Error {
  constructor() {
    super('응답이 너무 늦어요. 인터넷 연결을 확인해 주세요.')
    this.name = 'TimeoutError'
  }
}

export function defaultTimeoutMs(init: RequestInit): number {
  // 업로드는 5MB 사진 — 느린 업링크(0.4Mbps)에서도 끝나게 넉넉히 둔다
  if (typeof FormData !== 'undefined' && init.body instanceof FormData) return 120_000
  return (init.method ?? 'GET').toUpperCase() === 'GET' ? 10_000 : 15_000
}

export async function fetchWithTimeout(
  input: RequestInfo | URL,
  init: RequestInit = {},
  timeoutMs: number = defaultTimeoutMs(init),
): Promise<Response> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  // 호출자가 넘긴 signal(화면을 떠날 때 취소 등)도 그대로 존중한다
  const outer = init.signal ?? undefined
  const relay = () => controller.abort()
  if (outer) {
    if (outer.aborted) controller.abort()
    else outer.addEventListener('abort', relay, { once: true })
  }
  try {
    const res = await fetch(input, { ...init, signal: controller.signal })
    const buffered = await readWhole(res, controller.signal)
    if (isGatewayFailure(res.status)) markUnreachable()
    else markReachable()
    return buffered
  } catch (e) {
    // 호출자가 취소한 것(화면 이동 등)은 연결 상태와 무관하다 — 원래 예외 그대로
    if (outer?.aborted) throw e
    markUnreachable()
    // 우리 타이머가 끊은 것만 TimeoutError로 바꾼다
    if (controller.signal.aborted) throw new TimeoutError()
    throw e
  } finally {
    clearTimeout(timer)
    outer?.removeEventListener('abort', relay)
  }
}

/** 본문이 없어야 하는 상태 코드 — new Response(body, {status})가 본문을 거부한다 */
const NULL_BODY_STATUSES = new Set([101, 204, 205, 304])

/**
 * 본문을 제한 시간 안에 끝까지 읽어 새 Response로 돌려준다.
 * fetch 구현이 본문 읽기에 signal을 걸어주지 않는 경우까지 대비해 abort와 직접 경주시킨다.
 */
async function readWhole(res: Response, signal: AbortSignal): Promise<Response> {
  if (res.status < 200 || res.status > 599 || NULL_BODY_STATUSES.has(res.status)) return res
  const aborted = new Promise<never>((_resolve, reject) => {
    const fail = () => reject(new DOMException('aborted', 'AbortError'))
    if (signal.aborted) fail()
    else signal.addEventListener('abort', fail, { once: true })
  })
  const body = await Promise.race([res.arrayBuffer(), aborted])
  return new Response(body, { status: res.status, statusText: res.statusText, headers: res.headers })
}
