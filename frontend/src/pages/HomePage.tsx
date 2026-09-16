import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import mapPlaceholder from '../assets/map-placeholder.jpg'
import { CameraIcon } from '../components/customer/icons'

/*
 * 홈 화면 — Figma fileKey OZSYaIZ3UgdVIAdzmq5R8y
 *   · 접힘 상태 node 92:2   (홈화면)        시트가 55px만 노출
 *   · 펼침 상태 node 95:368 (홈화면 - 목록)  시트 상단 y=310, 지도 375×337
 * 두 프레임에서 달라지는 값은 지도 높이 · 카메라 버튼 높이 · 시트 위치 셋뿐이라
 * 한 컴포넌트의 두 상태(isListOpen)로 구현했다.
 */

/* ══════════════════════════════════════════════════════════════════════════
   🗺️  지도 배경 — 여기를 수정하세요 (EDIT HERE)
   ──────────────────────────────────────────────────────────────────────────
   지금은 임시 이미지다. Figma의 `image 1` 레이어를 PNG로 내보내
   src/assets/map.png 로 저장한 뒤 아래 import 경로만 바꾸면 된다.
   실제 지도 SDK(카카오/네이버)로 바꾸려면 아래 <div className="...bg-cover"/>
   를 지도 컴포넌트로 교체한다. 위치 클래스는 그대로 두면 레이아웃이 유지된다.
   ══════════════════════════════════════════════════════════════════════════ */
const MAP_IMAGE = mapPlaceholder

export type BoothCrowd = '여유' | '보통' | '혼잡'

export type BoothSummary = {
  id: string
  name: string
  /** 카드 두 번째 줄 문구 */
  description: string
  crowd: BoothCrowd
  thumbnailUrl?: string
}

/** API 연동 전 표시용 — 명세 연동 시 GET으로 교체 */
const SAMPLE_BOOTHS: BoothSummary[] = [
  { id: '1', name: '컴퓨터공학과 부스', description: '테이블 1개 남음', crowd: '여유' },
  { id: '2', name: '컴퓨터공학과 부스', description: '남은 테이블 없음', crowd: '혼잡' },
  { id: '3', name: '컴퓨터공학과 부스', description: '남은 테이블 없음', crowd: '혼잡' },
  { id: '4', name: '컴퓨터공학과 부스', description: '남은 테이블 없음', crowd: '혼잡' },
  { id: '5', name: '컴퓨터공학과 부스', description: '남은 테이블 없음', crowd: '혼잡' },
]

function BoothCard({ booth, onSelect }: { booth: BoothSummary; onSelect?: () => void }) {
  return (
    <li className="relative h-[92px] w-full">
      {onSelect && (
        <button
          type="button"
          onClick={onSelect}
          aria-label={`${booth.name}, ${booth.description}, ${booth.crowd}`}
          className="absolute inset-0 z-10 rounded-xl"
        />
      )}
      <div className="absolute inset-0 rounded-xl bg-neutral-50" />
      <div
        className="absolute left-2 top-4 h-[60px] w-[60px] rounded-lg bg-neutral-600 bg-cover bg-center"
        style={booth.thumbnailUrl ? { backgroundImage: `url(${booth.thumbnailUrl})` } : undefined}
      />
      <p className="absolute left-20 top-[21px] text-body-1 whitespace-nowrap text-black">{booth.name}</p>
      <p className="absolute left-20 top-[49px] text-body-3 whitespace-nowrap text-black">
        {booth.description}
      </p>
      <span className="absolute right-2 top-[23px] flex items-center justify-center rounded-3xl border border-black px-2 text-caption leading-[1.5] whitespace-nowrap text-black">
        {booth.crowd}
      </span>
    </li>
  )
}

export default function HomePage() {
  const navigate = useNavigate()
  const [isListOpen, setListOpen] = useState(false)
  const dragStartY = useRef<number | null>(null)
  // pointerup 이후 브라우저가 같은 엘리먼트에 click을 한 번 더 합성해서 보내므로,
  // 드래그로 이미 열고/닫았을 때는 그 뒤따라오는 click의 토글을 건너뛴다
  const didDragRef = useRef(false)

  const handlePointerDown = (e: React.PointerEvent<HTMLButtonElement>) => {
    dragStartY.current = e.clientY
    e.currentTarget.setPointerCapture(e.pointerId)
  }

  const handlePointerUp = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (dragStartY.current === null) return
    const dy = e.clientY - dragStartY.current
    dragStartY.current = null
    if (Math.abs(dy) > 12) {
      didDragRef.current = true
      setListOpen(dy < 0)
    }
  }

  const handleHandleClick = () => {
    if (didDragRef.current) {
      didDragRef.current = false
      return
    }
    setListOpen((open) => !open)
  }

  return (
    <div className="relative h-screen w-full overflow-clip bg-white">
      {/* 🗺️ 지도 — 접힘: 화면 전체 / 펼침: 상단 337px (위 EDIT HERE 참고) */}
      <div
        className={`absolute inset-x-0 top-0 bg-neutral-300 bg-cover bg-center transition-[height] duration-300 ease-out ${
          isListOpen ? 'h-[337px]' : 'h-full'
        }`}
        style={{ backgroundImage: `url(${MAP_IMAGE})` }}
      />

      {/* 카메라 버튼 — 두 프레임 모두 시트 상단에서 24px 위 (60×60, 오른쪽 24px) */}
      <button
        type="button"
        aria-label="코드 스캔"
        onClick={() => navigate('/scan')}
        className={`absolute right-6 z-20 flex h-[60px] w-[60px] items-center justify-center rounded-full bg-neutral-50/90 text-neutral-900 shadow-lg backdrop-blur transition-[bottom] duration-300 ease-out active:scale-95 ${
          isListOpen ? 'bottom-[526px]' : 'bottom-[79px]'
        }`}
      >
        <CameraIcon className="h-6 w-6" />
      </button>

      {/* 부스 목록 시트 — 접힘 55px 노출 / 펼침 502px 전체 */}
      <div
        className={`absolute inset-x-0 bottom-0 z-10 h-[502px] rounded-t-2xl bg-neutral-100 transition-transform duration-300 ease-out ${
          isListOpen ? 'translate-y-0' : 'translate-y-[447px]'
        }`}
      >
        <button
          type="button"
          aria-expanded={isListOpen}
          aria-label={isListOpen ? '부스 목록 접기' : '부스 목록 펼치기'}
          onClick={handleHandleClick}
          onPointerDown={handlePointerDown}
          onPointerUp={handlePointerUp}
          className="absolute inset-x-0 top-0 h-14 cursor-grab touch-none active:cursor-grabbing"
        >
          <span className="absolute left-1/2 top-3 h-1 w-8 -translate-x-1/2 rounded-3xl bg-neutral-800" />
        </button>

        <ul className="absolute inset-x-6 top-14 flex flex-col gap-5">
          {SAMPLE_BOOTHS.map((booth) => (
            // onSelect 미전달: 부스 상세 API 연동 전까지는 클릭 가능한 것처럼 보이지 않게 둔다
            <BoothCard key={booth.id} booth={booth} />
          ))}
        </ul>
      </div>
    </div>
  )
}
