import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { setSessionPartySize } from '../lib/customerSession'

/*
 * 테이블 이용 인원 선택 — Figma fileKey OZSYaIZ3UgdVIAdzmq5R8y, node 289:3672 (결제안내)
 *
 * Figma 프레임 이름은 "결제안내"지만 내용은 인원 선택 + 자릿세 안내다.
 * 기존 PaymentInfoPage(계좌 입금 안내)와는 다른 화면이라 파일을 새로 만들었다.
 *
 * 아래 MINT_* 값·아이콘 path는 Figma에서 실제로 내려받은 SVG 에셋(famicons:person-sharp,
 * boxicons:chair, akar-icons:plus/minus, Ellipse 2/3/4)에서 그대로 옮겼다.
 */
const MINT_ICON_OUTER = '#bcfee3'
const MINT_ICON_MID = '#9df6d2'
const MINT_ICON_CENTER = '#45fdb3'
const MINT_CIRCLE_CHAIR = '#b1ead3'
const MINT_CIRCLE_STEP = '#9cdac1'
const MINT_PANEL_BG = '#e8f9f2'
const MINT_PANEL_BORDER = '#b8dcd3'

const SEAT_FEE_PER_PERSON = 3000
const MIN_PARTY_SIZE = 1
const MAX_PARTY_SIZE = 20

/**
 * famicons:person-sharp — 상단 장식용. 인스턴스마다 색이 달라 공용 icons.tsx의
 * currentColor 기반 PersonIcon과는 별개로 둔다(이름 충돌 방지를 위해 Decor 접두어).
 */
function DecorPersonIcon({
  className,
  color,
  style,
}: {
  className?: string
  color: string
  style?: React.CSSProperties
}) {
  return (
    <svg
      className={className}
      style={style}
      viewBox="0 0 22 22"
      fill={color}
      xmlns="http://www.w3.org/2000/svg"
    >
      <path d="M11 11C11.9518 11 12.8823 10.7178 13.6737 10.1889C14.4651 9.66014 15.0819 8.90853 15.4462 8.02916C15.8104 7.14979 15.9057 6.18216 15.72 5.24863C15.5343 4.31509 15.076 3.45759 14.403 2.78455C13.7299 2.11151 12.8724 1.65316 11.9389 1.46747C11.0053 1.28178 10.0377 1.37708 9.15834 1.74133C8.27897 2.10558 7.52736 2.72241 6.99855 3.51382C6.46975 4.30523 6.1875 5.23568 6.1875 6.1875C6.1875 7.46385 6.69453 8.68793 7.59705 9.59045C8.49957 10.493 9.72365 11 11 11ZM11 12.375C8.01711 12.375 2.0625 14.2175 2.0625 17.875V20.625H19.9375V17.875C19.9375 14.2175 13.9829 12.375 11 12.375Z" />
    </svg>
  )
}

/** boxicons:chair */
function ChairIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 26 26" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path
        d="M20.5833 13H19.5V3.25C19.5 2.65417 19.0125 2.16667 18.4167 2.16667H7.58333C6.9875 2.16667 6.5 2.65417 6.5 3.25V13H5.41667C4.82083 13 4.33333 13.4875 4.33333 14.0833V18.4167C4.33333 19.0125 4.82083 19.5 5.41667 19.5H6.5V23.8333H8.66667V19.5H17.3333V23.8333H19.5V19.5H20.5833C21.1792 19.5 21.6667 19.0125 21.6667 18.4167V14.0833C21.6667 13.4875 21.1792 13 20.5833 13ZM8.66667 4.33333H17.3333V13H8.66667V4.33333ZM19.5 17.3333H6.5V15.1667H19.5V17.3333Z"
        fill="currentColor"
      />
    </svg>
  )
}

/** akar-icons:minus / akar-icons:plus — 공용 icons.tsx의 PlusIcon(basil:plus-solid)과는
 *  다른 모양이라(십자선 vs 채워진 플러스) 이름 충돌만 피해서 로컬에 둔다. */
function StepperMinusIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 34.6667 34" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path d="M33.6667 17H11" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  )
}

function StepperPlusIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 34 34" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path
        d="M17 28.3333V17M17 17V5.66667M17 17H28.3333M17 17H5.66667"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
      />
    </svg>
  )
}

/**
 * 상단 장식 — 사람 6명. Figma 절대좌표(121~255, 111~146)에서 묶음의 바운딩 박스(134×35, 좌상단 121,111)
 * 기준 상대좌표로 옮겼다. 바운딩 박스 자체를 화면 중앙에 고정해야 375px가 아닌 화면에서도
 * 묶음이 안 어긋난다(뷰포트 폭 기준 고정 left라 375px 전제였음).
 */
const PERSON_DECOR = [
  { left: 0, top: 13, color: MINT_ICON_OUTER },
  { left: 21, top: 5, color: MINT_ICON_MID },
  { left: 44, top: 0, color: MINT_ICON_CENTER },
  { left: 67, top: 0, color: MINT_ICON_CENTER },
  { left: 90, top: 5, color: MINT_ICON_MID },
  { left: 112, top: 13, color: MINT_ICON_OUTER },
]

export default function PartySizePage() {
  const navigate = useNavigate()
  const [partySize, setPartySize] = useState(2)

  return (
    <div className="relative h-screen w-full overflow-clip bg-neutral-50">
      {/* 사람 아이콘 장식 — 바운딩 박스(134×35)를 화면 중앙에 고정 */}
      <div className="absolute left-1/2 top-[111px] h-[35px] w-[134px] -translate-x-1/2">
        {PERSON_DECOR.map((person) => (
          <DecorPersonIcon
            key={`${person.left}-${person.top}`}
            color={person.color}
            className="absolute h-[22px] w-[22px]"
            style={{ left: person.left, top: person.top }}
          />
        ))}
      </div>

      <h1 className="text-heading-2 absolute left-1/2 top-[165px] -translate-x-1/2 text-center whitespace-nowrap text-black">
        <span className="mb-[2px] block leading-[1.2]">테이블 이용 인원을</span>
        <span className="block leading-[1.2]">선택해주세요.</span>
      </h1>
      <p className="text-body-2 absolute left-1/2 top-[239px] -translate-x-1/2 text-center whitespace-nowrap text-neutral-400">
        인원수에 따라 자릿세가 추가됩니다.
      </p>

      {/* 자릿세 안내 카드 — 뷰포트 폭 고정 좌표 대신 패널 내부 flex로 배치(375px 아닌 화면에서도 안 밀림) */}
      <div
        className="absolute inset-x-[18px] top-[277px] flex h-[68px] items-center justify-between rounded-[10px] border px-[13px]"
        style={{ backgroundColor: MINT_PANEL_BG, borderColor: MINT_PANEL_BORDER }}
      >
        <div className="flex items-center gap-[10px]">
          <div
            className="flex h-[47px] w-[47px] shrink-0 items-center justify-center rounded-full text-black"
            style={{ backgroundColor: MINT_CIRCLE_CHAIR }}
          >
            <ChairIcon className="h-[26px] w-[26px]" />
          </div>
          <p className="text-body-2 whitespace-nowrap text-black">자릿세</p>
        </div>
        <p className="text-body-2 whitespace-nowrap text-black">
          인당 {SEAT_FEE_PER_PERSON.toLocaleString()}원
        </p>
      </div>

      {/* 인원 스테퍼 — 자릿세 카드와 동일하게 패널 내부 flex로 배치 */}
      <div
        className="absolute inset-x-[18px] top-[372px] flex h-[76px] items-center justify-between rounded-[45px] border px-[15px]"
        style={{ backgroundColor: MINT_PANEL_BG, borderColor: MINT_PANEL_BORDER }}
      >
        <button
          type="button"
          aria-label="인원 줄이기"
          disabled={partySize <= MIN_PARTY_SIZE}
          onClick={() => setPartySize((n) => Math.max(MIN_PARTY_SIZE, n - 1))}
          className="flex h-[45px] w-[45px] shrink-0 items-center justify-center rounded-full text-black disabled:opacity-40"
          style={{ backgroundColor: MINT_CIRCLE_STEP }}
        >
          <StepperMinusIcon className="h-[34px] w-[34px]" />
        </button>
        <div className="flex flex-1 items-baseline justify-center gap-[18px]">
          <p
            aria-live="polite"
            className="text-[40px] leading-normal font-bold tracking-[-0.04em] whitespace-nowrap text-black"
          >
            {partySize}
          </p>
          <p className="text-body-1 whitespace-nowrap text-black">명</p>
        </div>
        <button
          type="button"
          aria-label="인원 늘리기"
          disabled={partySize >= MAX_PARTY_SIZE}
          onClick={() => setPartySize((n) => Math.min(MAX_PARTY_SIZE, n + 1))}
          className="flex h-[45px] w-[45px] shrink-0 items-center justify-center rounded-full text-black disabled:opacity-40"
          style={{ backgroundColor: MINT_CIRCLE_STEP }}
        >
          <StepperPlusIcon className="h-[34px] w-[34px]" />
        </button>
      </div>

      {/* 주문 시작하기 */}
      <button
        type="button"
        onClick={() => {
          setSessionPartySize(partySize)
          navigate('/order', { replace: true })
        }}
        className="text-body-1 absolute inset-x-[18px] bottom-9 h-[50px] rounded-xl bg-black text-white"
      >
        주문 시작하기
      </button>
    </div>
  )
}
