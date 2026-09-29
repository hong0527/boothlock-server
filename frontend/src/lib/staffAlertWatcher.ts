import { diffArrivals, type ArrivalSnapshot, type Arrivals } from './newArrivals'
import type { ResumeReason } from './onResume'

/**
 * 직원용 앱 공통 알림 감시 — 어느 직원 화면에 있든 새 승인대기 주문·직원호출을 잡아 알린다(StaffAlertWatcher가 앱에 하나만 둔다).
 *
 * <p>예전에는 주문현황(OrderStatusPage)이 자기 폴링으로 알렸다. 그래서 다른 화면에 있으면 울리지 않았고, 돌아올 때마다
 * 스냅샷이 처음부터 다시 시작돼 그 사이 들어온 것은 "첫 조회"로 묻혔다. 여기서는 화면 이동과 무관하게 한 벌만 돈다.
 *
 * <p>판단은 lib/newArrivals 그대로다(id 집합 비교, 첫 조회는 알리지 않음). 알림을 껐는지는 여기서 보지 않는다 —
 * 끈 동안에도 감시는 계속해 스냅샷을 최신으로 두고, 소리만 onArrivals 쪽에서 막는다. 그래야 다시 켰을 때 끈 동안 쌓인 것이
 * 한꺼번에 울리지 않는다. 조회가 실패한 주기는 스냅샷을 바꾸지 않는다 — 다음 성공 때 그 사이 것도 새 것으로 잡힌다
 * (얼마나 늦었든 알린다).
 *
 * <p>늦은 것 무음(newArrivals suppressLate)은 숨겨졌던 탭이 다시 보인 직후(onResume 'visible')에 실제로 도는 조회 한 번에만
 * 건다. 그때 앞 조회가 아직 안 끝나 건너뛰면 다음에 도는 조회가 이어받는다. 그 조회가 끝나면(성공이든 실패든) 다시 평소대로다.
 * 재연결(onResume 'online')·평소 주기·숨겨진 채 느려진 주기 조회는 늦었어도 알린다.
 */

// 직원용 화면 — 손님(/t, /order, /cart …)·방문자(/home, /scan)·로그인 화면에서는 감시하지 않는다.
// 같은 브라우저의 다른 탭에서 손님 화면을 시험해도 그 탭까지 울리지 않게
const STAFF_PATHS = ['/orders', '/tables', '/settings']

/** 알림 감시를 돌릴 직원 화면인가(하위 경로 포함) */
export function isStaffPath(pathname: string): boolean {
  return STAFF_PATHS.some((path) => pathname === path || pathname.startsWith(`${path}/`))
}

/** 주문현황과 같은 주기(명세서 O10 권장 3~5초). 요청은 승인대기 조회 하나뿐이다(호출 목록이 함께 실려 온다) */
export const ALERT_POLL_INTERVAL_MS = 5000

export type ArrivalWatcherDeps = {
  /** 지금 상태(승인대기 주문 id·미확인 호출 id). 실패하면 던진다 */
  fetchSnapshot: () => Promise<ArrivalSnapshot>
  /** 새로 생긴 게 있을 때만 불린다 */
  onArrivals: (arrivals: Arrivals) => void
  /** 폰 잠금 해제·재연결 때 바로 한 번 더 보게 한다(lib/onResume). 'visible'이면 따라잡기 조회다. 반환값은 해제 함수 */
  onResume?: (listener: (reason: ResumeReason) => void) => () => void
  intervalMs?: number
}

export type ArrivalWatcher = {
  /** 감시 시작 — 이미 돌고 있는 감시가 있으면(이 감시든 다른 감시든) 아무것도 하지 않고 false */
  start: () => boolean
  /** 감시 중지 — 타이머·재개 구독을 풀고 스냅샷을 버린다. 늦게 도착한 응답은 무시한다 */
  stop: () => void
}

// 앱 전체에서 동시에 도는 감시는 하나뿐이다 — 실수로 두 군데서 시작해도 폴링·알림음이 두 배가 되지 않게
let runningWatcher: object | null = null

export function createArrivalWatcher({
  fetchSnapshot,
  onArrivals,
  onResume,
  intervalMs = ALERT_POLL_INTERVAL_MS,
}: ArrivalWatcherDeps): ArrivalWatcher {
  const self = {}
  let snapshot: ArrivalSnapshot | null = null
  let timer: ReturnType<typeof setInterval> | null = null
  let offResume: (() => void) | null = null
  let inFlight = false
  // stop 할 때마다 올린다 — 멈춘 뒤에 도착한 응답이 스냅샷을 되살리거나 소리를 내지 않게
  let generation = 0
  // 탭이 다시 보였다 — 다음에 실제로 도는 조회 하나를 따라잡기로 본다(위 설명)
  let catchUpPending = false

  async function poll() {
    // 앞 조회가 아직이면 건너뛴다 — 느린 회선에서 요청이 겹겹이 쌓이지 않게(따라잡기 표시는 남겨 다음 조회가 이어받는다)
    if (inFlight) return
    inFlight = true
    const current = generation
    const suppressLate = catchUpPending
    catchUpPending = false
    try {
      const next = await fetchSnapshot()
      if (current !== generation) return
      const arrivals = diffArrivals(snapshot, next, { suppressLate })
      snapshot = next
      if (arrivals.newPendingOrders + arrivals.newCalls > 0) onArrivals(arrivals)
    } catch {
      // 실패한 주기는 스냅샷을 그대로 둔다(위 설명)
    } finally {
      if (current === generation) inFlight = false
    }
  }

  return {
    start() {
      if (runningWatcher) return false
      runningWatcher = self
      void poll()
      timer = setInterval(() => void poll(), intervalMs)
      offResume = onResume?.((reason) => {
        if (reason === 'visible') catchUpPending = true
        void poll()
      }) ?? null
      return true
    },
    stop() {
      if (runningWatcher !== self) return
      runningWatcher = null
      if (timer) clearInterval(timer)
      timer = null
      offResume?.()
      offResume = null
      generation += 1
      inFlight = false
      catchUpPending = false
      snapshot = null
    },
  }
}
