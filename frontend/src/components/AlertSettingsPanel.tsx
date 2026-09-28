import { useEffect, useState } from 'react'
import AlertSwitch from './AlertSwitch'
import {
  getAlertVolume,
  isAlertPreferred,
  setAlertVolume,
  subscribeAlertPreference,
  turnAlertsOff,
  turnAlertsOn,
} from '../lib/staffAlert'

/**
 * 설정 목록의 "알림 설정" 항목을 펼치면 나오는 내용 — 알림음 켜고 끔과 알림음 크기.
 * 별도 화면·카드처럼 보이지 않게 목록과 같은 구분선·글자 체계를 쓰고, 항목 아래에 한 단계 들여 놓는다.
 * 켜고 끔은 주문현황의 빠른 전환과 같은 저장값 하나를 쓴다(staffAlert) — 어느 쪽에서 바꿔도 다른 쪽이 따라온다.
 * 알림음 크기는 이 웹앱이 내는 알림음에만 곱한다(기기 볼륨은 그대로). 알림을 꺼도 값은 남는다.
 * 소리는 어느 직원 화면에 있든 울린다 — 새 주문·호출 감시는 앱 공통 StaffAlertWatcher가 한다
 */
export default function AlertSettingsPanel({ id }: { id?: string }) {
  const [on, setOn] = useState(() => isAlertPreferred())
  const [volume, setVolume] = useState(() => getAlertVolume())

  useEffect(() => subscribeAlertPreference(setOn), [])

  // 켜기는 이 클릭 안에서 오디오를 풀어야 iOS에서 소리가 난다(turnAlertsOn) — 상태는 구독이 맞춘다
  const toggle = () => (on ? turnAlertsOff() : turnAlertsOn())

  const changeVolume = (value: number) => {
    setVolume(value)
    setAlertVolume(value)
  }

  return (
    <div id={id} className="flex flex-col border-b border-neutral-100 pb-5 pl-4">
      <div className="flex min-h-[64px] items-center justify-between gap-4 border-b border-neutral-100 py-3">
        <div className="min-w-0">
          <p className="text-base leading-[1.3] font-semibold text-neutral-900">알림음</p>
          <p className="mt-1 text-sm leading-[1.4] text-neutral-500">주문 및 직원 호출 알림을 받습니다</p>
        </div>
        <AlertSwitch on={on} onToggle={toggle} label={on ? 'ON' : 'OFF'} ariaLabel="알림음" />
      </div>

      <div className="py-3">
        <div className="flex items-center justify-between gap-4">
          <div className="min-w-0">
            <p className="text-base leading-[1.3] font-semibold text-neutral-900">알림음 크기</p>
            <p className="mt-1 text-sm leading-[1.4] text-neutral-500">알림음의 재생 크기를 조절합니다</p>
          </div>
          <span className="shrink-0 text-lg font-semibold tabular-nums text-neutral-900">{volume}%</span>
        </div>
        <div className="mt-3 flex items-center gap-3">
          <span aria-hidden="true" className="text-lg">🔈</span>
          <input
            type="range"
            min={0}
            max={100}
            step={5}
            value={volume}
            onChange={(event) => changeVolume(Number(event.target.value))}
            aria-label="알림음 크기"
            aria-valuetext={`${volume}%`}
            className="h-10 w-full cursor-pointer accent-primary-300"
          />
          <span aria-hidden="true" className="text-lg">🔊</span>
        </div>
      </div>

      <p className="text-sm leading-[1.4] text-neutral-500">
        직원용 앱을 사용하는 동안 주문 및 직원 호출 알림을 받을 수 있어요. 켤 때 확인음이 한 번 울려요.
      </p>
    </div>
  )
}
