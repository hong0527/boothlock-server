type AlertSwitchProps = {
  on: boolean
  onToggle: () => void
  /** 스위치 옆에 보이는 현재 상태 글자 — 색만으로 켜짐/꺼짐을 구분하지 않게 늘 글자로도 쓴다 */
  label: string
  /** 스크린리더용 이름 — 보이는 글자가 상태("ON")뿐일 때 무엇의 스위치인지 알려 준다 */
  ariaLabel?: string
}

/**
 * 알림 켜고 끔 스위치 — 설정 화면(알림 설정 카드)과 주문현황의 빠른 전환이 같은 모양을 쓴다.
 * 켜짐/꺼짐을 색·글자·손잡이 위치 세 가지로 동시에 보여 준다(켜짐: 초록 채움·손잡이 오른쪽, 꺼짐: 흰 바탕 회색 테두리·손잡이 왼쪽).
 * 높이 44px 이상 — 바쁜 손으로도 누르기 쉽게
 */
export default function AlertSwitch({ on, onToggle, label, ariaLabel }: AlertSwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={ariaLabel}
      onClick={onToggle}
      className={`flex h-11 shrink-0 items-center gap-3 rounded-full border-2 py-1 pr-1.5 pl-4 text-base leading-[1.2] font-semibold tracking-[-0.04em] ${
        on ? 'border-primary-300 bg-primary-300 text-neutral-50' : 'border-neutral-300 bg-white text-neutral-500'
      }`}
    >
      <span>{label}</span>
      <span aria-hidden="true" className={`relative h-7 w-12 rounded-full ${on ? 'bg-primary-500' : 'bg-neutral-200'}`}>
        <span
          className={`absolute top-1 h-5 w-5 rounded-full bg-white shadow transition-[left] ${on ? 'left-6' : 'left-1'}`}
        />
      </span>
    </button>
  )
}
