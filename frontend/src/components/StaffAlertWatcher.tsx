import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { apiFetch } from '../lib/apiFetch'
import { getAuthToken } from '../lib/auth'
import { snapshotOf } from '../lib/newArrivals'
import { onResume } from '../lib/onResume'
import {
  acquireWakeLock,
  isAlertPreferred,
  listenForAudioUnlock,
  playCallAlert,
  playOrderAlert,
  releaseWakeLock,
  resumeAudio,
  stopAlertSounds,
  subscribeAlertPreference,
  suspendAudio,
  vibrate,
} from '../lib/staffAlert'
import { createArrivalWatcher, isStaffPath } from '../lib/staffAlertWatcher'
import type { DashboardResponse } from '../types/dashboard'

/**
 * 직원용 앱 공통 알림 — 어느 직원 화면에 있든 새 주문·직원호출에 알림음을 낸다. App에 하나만 둔다(화면을 그리지 않는다).
 * 라우트 바깥(App)에 있어 직원 화면끼리 이동해도 감시·스냅샷이 그대로 유지된다(lib/staffAlertWatcher).
 * 로그인 토큰이 없으면 돌지 않는다 — apiFetch는 토큰이 없으면 로그인 화면으로 보내 버리므로 부르지도 않는다.
 * 로그아웃(토큰 삭제 뒤 로그인 화면 이동)이면 멈추고 스냅샷을 버린다 — 다시 로그인하면 첫 조회부터(쌓여 있던 건 알리지 않음).
 */
export default function StaffAlertWatcher() {
  const { pathname } = useLocation()
  const active = isStaffPath(pathname) && !!getAuthToken()
  const [alertOn, setAlertOn] = useState(() => isAlertPreferred())
  // 감시의 콜백은 시작할 때 한 번 만든다 — 켜고 끔은 상태가 아니라 ref로 최신 값을 본다
  const alertOnRef = useRef(alertOn)

  // 알림 켜고 끔(설정 화면·주문현황·다른 탭)을 따라간다. 다른 탭에서 끈 경우 이 탭에 예약된 소리도 끊고 오디오를 재운다
  useEffect(() => subscribeAlertPreference((on) => {
    if (on === alertOnRef.current) return
    if (!on) {
      stopAlertSounds()
      suspendAudio()
    }
    alertOnRef.current = on
    setAlertOn(on)
  }), [])

  useEffect(() => {
    if (!active) return
    const watcher = createArrivalWatcher({
      fetchSnapshot: async () => {
        if (!getAuthToken()) throw new Error('로그아웃됨')
        // 승인대기 응답에 미확인 호출(calls)도 부스 전체가 실려 온다 — 요청 하나로 둘 다 본다
        const res = await apiFetch('/api/v1/admin/orders?status=PENDING_APPROVAL')
        if (!res.ok) throw new Error(`알림 조회 실패 (${res.status})`)
        const body: DashboardResponse = await res.json()
        // 서버 시각(Date 헤더) — 탭 복귀 직후 따라잡기 조회에서 오래된 주문·호출을 소리로 알리지 않는 기준
        // (newArrivals LATE_ALERT_MS). 없거나 못 읽으면 넘기지 않는다(전부 알린다 — CORS 분리 배포면 Date가 안 읽혀 이쪽이다)
        const serverNow = Date.parse(res.headers.get('Date') ?? '')
        return snapshotOf(body.orders, body.calls ?? [], Number.isFinite(serverNow) ? serverNow : undefined)
      },
      // 알림을 꺼 두었으면 소리·진동만 내지 않는다(감시는 계속 — 다시 켰을 때 지난 것이 한꺼번에 울리지 않게).
      // 주문과 호출이 한 번에 오면 staffAlert가 주문음이 끝난 뒤에 호출음을 이어 붙인다(겹치지 않게)
      onArrivals: (arrivals) => {
        if (!alertOnRef.current) return
        if (arrivals.newPendingOrders > 0) playOrderAlert()
        if (arrivals.newCalls > 0) playCallAlert()
        vibrate()
      },
      onResume,
    })
    watcher.start()
    return () => watcher.stop()
  }, [active])

  // 알림이 켜져 있으면 화면 꺼짐 방지를 잡고, 탭 전환·잠금 해제로 돌아올 때마다 다시 잡는다(브라우저가 자동으로 풀어서).
  // 새로고침 뒤에는 오디오가 다시 잠겨 있으므로 화면을 누르는 순간 풀어 준다(iOS 자동재생 정책, listenForAudioUnlock).
  // 돌아올 때는 오디오도 다시 깨운다 — iOS는 전화 등으로 끊긴('interrupted') 오디오를 스스로 되살리지 않는다.
  // 예전에는 주문현황에만 있었다 — 이제 어느 직원 화면에서든 한 번 누르면 소리가 풀린다
  useEffect(() => {
    if (!active || !alertOn || typeof document === 'undefined') return
    void acquireWakeLock()
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return
      void acquireWakeLock()
      resumeAudio()
    }
    document.addEventListener('visibilitychange', onVisible)
    const stopUnlock = listenForAudioUnlock()
    return () => {
      document.removeEventListener('visibilitychange', onVisible)
      stopUnlock()
      releaseWakeLock()
    }
  }, [active, alertOn])

  return null
}
