import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

/*
 * 테이블 이용 인원 선택 — Figma fileKey OZSYaIZ3UgdVIAdzmq5R8y, node 289:3672 (결제안내)
 *
 * Figma 프레임 이름은 "결제안내"지만 내용은 인원 선택 + 자릿세 안내다.
 * 기존 PaymentInfoPage(계좌 입금 안내)와는 다른 화면이라 파일을 새로 만들었다.
 *
 * 색상 주의: 아래 MINT_* 는 Figma 원본 SVG를 내려받지 못해 렌더 화면에서 읽은
 * 근사값이다. 디자이너에게 아이콘을 export 받으면 이 상수만 바꾸면 된다.
 */
const MINT_ICON_OUTER = '#c6f1e1'
const MINT_ICON_MID = '#8fe8c8'
const MINT_ICON_CENTER = '#4ce0af'
const MINT_CIRCLE_CHAIR = '#ace3d0'
const MINT_CIRCLE_STEP = '#9dd9c2'
const MINT_PANEL_BG = '#e8f9f2'
const MINT_PANEL_BORDER = '#b8dcd3'

const SEAT_FEE_PER_PERSON = 3000
const MIN_PARTY_SIZE = 1

/** famicons:person-sharp */
function PersonIcon({
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
      <path d="M11 11.46a3.9 3.9 0 1 0 0-7.79 3.9 3.9 0 0 0 0 7.79Z" />
      <path d="M11 13.06c-3.9 0-7.33 2.1-7.33 4.62v1.65h14.66v-1.65c0-2.52-3.43-4.62-7.33-4.62Z" />
    </svg>
  )
}

/** boxicons:chair */
function ChairIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 26 26" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path
        d="M7.6 3.3h10.8a1.4 1.4 0 0 1 1.4 1.5l-.7 8.2H6.9l-.7-8.2a1.4 1.4 0 0 1 1.4-1.5Z"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
      <path
        d="M5.2 14.6h15.6M7.4 17.9v4.8M18.6 17.9v4.8M6.3 14.6l.5 3.3h12.4l.5-3.3"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/** akar-icons:minus / akar-icons:plus */
function MinusIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 34 34" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path d="M8 17h18" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
    </svg>
  )
}

function PlusIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 34 34" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path d="M17 8v18M8 17h18" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
    </svg>
  )
}

/** 상단 장식 — 사람 6명. Figma 좌표(left, top)와 색을 그대로 옮겼다. */
const PERSON_DECOR = [
  { left: 121, top: 124, color: MINT_ICON_OUTER },
  { left: 142, top: 116, color: MINT_ICON_MID },
  { left: 165, top: 111, color: MINT_ICON_CENTER },
  { left: 188, top: 111, color: MINT_ICON_CENTER },
  { left: 211, top: 116, color: MINT_ICON_MID },
  { left: 233, top: 124, color: MINT_ICON_OUTER },
]

export default function PartySizePage() {
  const navigate = useNavigate()
  const [partySize, setPartySize] = useState(2)

  return (
    <div className="relative h-screen w-full overflow-clip bg-neutral-50">
      {/* 사람 아이콘 장식 */}
      {PERSON_DECOR.map((person) => (
        <PersonIcon
          key={`${person.left}-${person.top}`}
          color={person.color}
          className="absolute h-[22px] w-[22px]"
          style={{ left: person.left, top: person.top }}
        />
      ))}

      <h1 className="text-heading-2 absolute left-1/2 top-[165px] -translate-x-1/2 text-center leading-[1.2] whitespace-nowrap text-black">
        테이블 이용 인원을
        <br />
        선택해주세요.
      </h1>
      <p className="text-body-2 absolute left-1/2 top-[239px] -translate-x-1/2 text-center whitespace-nowrap text-neutral-400">
        인원수에 따라 자릿세가 추가됩니다.
      </p>

      {/*
       * 아래 요소들은 Figma 프레임(375 × 812) 좌표를 그대로 쓴다.
       * 패널 안쪽이 아니라 화면 기준으로 두는 이유: 패널에 1px 테두리가 있어서
       * 안쪽 기준으로 잡으면 아이콘·텍스트가 전부 1px씩 밀린다.
       */}

      {/* 자릿세 안내 카드 */}
      <div
        className="absolute inset-x-[18px] top-[277px] h-[68px] rounded-[10px] border"
        style={{ backgroundColor: MINT_PANEL_BG, borderColor: MINT_PANEL_BORDER }}
      />
      <div
        className="absolute left-[31px] top-[288px] flex h-[47px] w-[47px] items-center justify-center rounded-full text-black"
        style={{ backgroundColor: MINT_CIRCLE_CHAIR }}
      >
        <ChairIcon className="h-[26px] w-[26px]" />
      </div>
      <p className="text-body-2 absolute left-[105px] top-[303px] -translate-x-1/2 whitespace-nowrap text-black">
        자릿세
      </p>
      <p className="text-body-2 absolute left-[307.5px] top-[303px] -translate-x-1/2 whitespace-nowrap text-black">
        인당 {SEAT_FEE_PER_PERSON.toLocaleString()}원
      </p>

      {/* 인원 스테퍼 */}
      <div
        className="absolute inset-x-[18px] top-[372px] h-[76px] rounded-[45px] border"
        style={{ backgroundColor: MINT_PANEL_BG, borderColor: MINT_PANEL_BORDER }}
      />
      <button
        type="button"
        aria-label="인원 줄이기"
        disabled={partySize <= MIN_PARTY_SIZE}
        onClick={() => setPartySize((n) => Math.max(MIN_PARTY_SIZE, n - 1))}
        className="absolute left-[33px] top-[388px] flex h-[45px] w-[45px] items-center justify-center rounded-full text-black disabled:opacity-40"
        style={{ backgroundColor: MINT_CIRCLE_STEP }}
      >
        <MinusIcon className="h-[34px] w-[34px]" />
      </button>
      <p
        aria-live="polite"
        className="absolute left-[187.5px] top-[386px] -translate-x-1/2 text-[40px] leading-normal font-bold tracking-[-0.04em] whitespace-nowrap text-black"
      >
        {partySize}
      </p>
      <p className="text-body-1 absolute left-[226px] top-[401px] -translate-x-1/2 whitespace-nowrap text-black">
        명
      </p>
      <button
        type="button"
        aria-label="인원 늘리기"
        onClick={() => setPartySize((n) => n + 1)}
        className="absolute left-[295px] top-[388px] flex h-[45px] w-[45px] items-center justify-center rounded-full text-black"
        style={{ backgroundColor: MINT_CIRCLE_STEP }}
      >
        <PlusIcon className="h-[34px] w-[34px]" />
      </button>

      {/* 주문 시작하기 */}
      <button
        type="button"
        onClick={() => navigate('/order', { state: { partySize } })}
        className="text-body-1 absolute inset-x-[18px] bottom-9 h-[50px] rounded-xl bg-black text-white"
      >
        주문 시작하기
      </button>
    </div>
  )
}
