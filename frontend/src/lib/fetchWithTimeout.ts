/**
 * 제한 시간이 있는 fetch.
 *
 * 축제 현장은 200~300명이 모이면 인터넷이 잘 안 터진다(실제 제보). 약한 신호에서는 TCP 재전송 때문에
 * 요청 하나가 수 분씩 매달린다. 그동안 pollGuard가 "앞 요청이 아직 돈다"며 다음 폴링을 전부 건너뛰고,
 * 오류도 안 나서 운영자 화면이 아무 표시 없이 옛 주문 목록에 굳었다. 버튼도 같이 잠겼다.
 * 제한 시간을 두면 매달린 요청이 실패로 끝나고, 화면은 오류를 보여준 뒤 다음 폴링에서 회복한다.
 *
 * 기본값: 조회(GET) 10초, 변경 15초, 파일 업로드(FormData) 60초. 필요한 곳은 timeoutMs로 따로 준다.
 * 제한 시간은 응답 헤더가 도착할 때까지만 잰다(본문 읽기는 재지 않는다).
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
  if (typeof FormData !== 'undefined' && init.body instanceof FormData) return 60_000
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
    if (isGatewayFailure(res.status)) markUnreachable()
    else markReachable()
    return res
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
