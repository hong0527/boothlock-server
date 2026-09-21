/**
 * 폴링 겹침·응답 역전 가드 — 주문현황·테이블 목록·손님 주문내역이 공유한다.
 *
 * <p>막는 것 두 가지:
 * <ul>
 *   <li><b>겹침</b> — 요청이 주기보다 오래 걸리면 다음 주기가 또 던져 느릴수록 쌓인다.
 *       주문현황은 한 번에 3개(진행·완료·취소)라 더 빨리 불어난다. 이미 요청 중이면 그 주기를 건너뛴다</li>
 *   <li><b>응답 역전</b> — 늦게 출발한 응답이 먼저 도착하면, 뒤늦게 온 옛 응답이 최신 화면을 덮는다.
 *       방금 완료 처리한 주문이 진행 탭에 되살아났다가 다음 폴링에 사라지는 식으로 보인다.
 *       자기보다 나중에 시작된 요청이 있으면 결과를 버린다</li>
 * </ul>
 *
 * <p>액션 직후의 즉시 갱신은 건너뛰면 안 되므로 {@code skipIfBusy}는 폴링에서만 켠다.
 */
export type PollGuard = {
  /** 요청 시작. 건너뛰어야 하면 null. 아니면 이 실행의 id */
  begin: (skipIfBusy?: boolean) => number | null
  /** 이 실행이 아직 최신인가 — 아니면 응답을 버린다 */
  isLatest: (runId: number) => boolean
  /** 성공·실패 무관하게 반드시 호출 (finally) */
  end: () => void
}

export function createPollGuard(): PollGuard {
  let running = 0
  let latest = 0
  return {
    begin(skipIfBusy = false) {
      if (skipIfBusy && running > 0) return null
      running += 1
      return ++latest
    },
    isLatest: (runId: number) => runId === latest,
    end() {
      running -= 1
    },
  }
}
