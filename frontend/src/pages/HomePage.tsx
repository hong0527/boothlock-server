import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { CameraIcon } from '../components/customer/icons'
import { apiUrl, assetUrl } from '../lib/apiBase'
import { containRect, crowdLevel, pinPosition, seatDescription, type CrowdLevel } from '../lib/eventMap'
import {
  BOOTH_CATEGORY_LABEL,
  type BoothCategory,
  type BoothListResponse,
  type EventBooth,
  type EventMapResponse,
} from '../types/event'

/*
 * 홈 화면 — Figma fileKey OZSYaIZ3UgdVIAdzmq5R8y
 *   · 접힘 상태 node 92:2   (홈화면)        시트가 55px만 노출
 *   · 펼침 상태 node 95:368 (홈화면 - 목록)  시트 상단 y=310, 지도 375×337
 * 두 프레임에서 달라지는 값은 지도 높이 · 카메라 버튼 높이 · 시트 위치 셋뿐이라
 * 한 컴포넌트의 두 상태(isListOpen)로 구현했다.
 *
 * 데이터 (API 명세서 E1·E2, 인증 없음):
 *   · 지도 = E2 GET /api/v1/event/map — 진입 시 한 번. 404(약도 미등록)면 지도 없이 목록만
 *   · 부스 목록·좌석 = E1 GET /api/v1/event/booths?category= — 10~15초 폴링(서버 캐시 10초)
 *   · 핀 위치 = booth.mapX·mapY(0~10000 상대값) → 이미지 표시 영역 기준 픽셀 환산 (lib/eventMap.ts)
 */

const BOOTH_POLL_INTERVAL_MS = 12_000

export type BoothCrowd = CrowdLevel

const CATEGORY_FILTERS: { value: BoothCategory | null; label: string }[] = [
  { value: null, label: '전체' },
  ...(Object.keys(BOOTH_CATEGORY_LABEL) as BoothCategory[]).map((value) => ({
    value,
    label: BOOTH_CATEGORY_LABEL[value],
  })),
]

// 핀·뱃지 색 — 여유/혼잡이 한눈에 갈리게. 주문 마감 부스는 회색으로 묶는다
const CROWD_PIN_CLASS: Record<CrowdLevel, string> = {
  여유: 'bg-primary-200',
  혼잡: 'bg-amber-400',
}

function BoothCard({ booth, onSelect }: { booth: EventBooth; onSelect?: () => void }) {
  const crowd = crowdLevel(booth.tables.total, booth.tables.empty)
  const description = seatDescription(booth.tables.total, booth.tables.empty, booth.isOpen)
  return (
    <li className="relative h-[92px] w-full">
      {onSelect && (
        <button
          type="button"
          onClick={onSelect}
          aria-label={`${booth.name}, ${description}, ${crowd}`}
          className="absolute inset-0 z-10 rounded-xl"
        />
      )}
      <div className="absolute inset-0 rounded-xl bg-neutral-50" />
      {/* 썸네일 — E1 응답에 부스 사진이 없어 자리만 둔다(분류 라벨로 대신 구분) */}
      <div className="absolute left-2 top-4 flex h-[60px] w-[60px] items-center justify-center rounded-lg bg-neutral-600 text-caption text-neutral-50">
        {booth.category ? BOOTH_CATEGORY_LABEL[booth.category] : ''}
      </div>
      <p className="absolute left-20 top-[21px] text-body-1 whitespace-nowrap text-black">{booth.name}</p>
      <p className={`absolute left-20 top-[49px] text-body-3 whitespace-nowrap ${booth.isOpen ? 'text-black' : 'text-neutral-400'}`}>
        {description}
      </p>
      <span className="absolute right-2 top-[23px] flex items-center justify-center rounded-3xl border border-black px-2 text-caption leading-[1.5] whitespace-nowrap text-black">
        {crowd}
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

  const [booths, setBooths] = useState<EventBooth[]>([])
  const [boothsError, setBoothsError] = useState<string | null>(null)
  const [category, setCategory] = useState<BoothCategory | null>(null)
  // undefined = 아직 모름(요청 중), null = 약도 미등록(404) → 지도 없이 목록만
  const [map, setMap] = useState<EventMapResponse | null | undefined>(undefined)
  const mapContainerRef = useRef<HTMLDivElement>(null)
  const [mapBox, setMapBox] = useState({ width: 0, height: 0 })

  // E2 약도 — 진입 시 한 번. 404면 지도 없이 목록만 (명세 E2)
  useEffect(() => {
    let cancelled = false
    fetch(apiUrl('/api/v1/event/map'))
      .then((res) => (res.ok ? (res.json() as Promise<EventMapResponse>) : null))
      .then((data) => {
        if (!cancelled) setMap(data)
      })
      .catch(() => {
        if (!cancelled) setMap(null)
      })
    return () => {
      cancelled = true
    }
  }, [])

  // E1 부스 목록·좌석 — 카테고리 필터는 서버 파라미터(대소문자 무시, 모르는 값이면 빈 배열)
  const fetchBooths = useCallback(async () => {
    try {
      const query = category ? `?category=${encodeURIComponent(category)}` : ''
      const res = await fetch(apiUrl(`/api/v1/event/booths${query}`))
      if (!res.ok) throw new Error(`부스 현황을 불러오지 못했어요 (${res.status})`)
      const data: BoothListResponse = await res.json()
      setBooths(data.booths)
      setBoothsError(null)
    } catch (err) {
      setBoothsError(err instanceof Error ? err.message : '부스 현황을 불러오지 못했어요.')
    }
  }, [category])

  useEffect(() => {
    fetchBooths()
    const id = setInterval(fetchBooths, BOOTH_POLL_INTERVAL_MS)
    return () => clearInterval(id)
  }, [fetchBooths])

  // 지도 컨테이너 크기 — 접힘/펼침 전환(height transition)과 회전에 따라 핀 위치를 다시 환산한다
  useEffect(() => {
    const el = mapContainerRef.current
    if (!el) return
    // ResizeObserver는 observe() 직후 첫 콜백을 한 번 내려주므로 초기 크기도 여기서 잡힌다
    const observer = new ResizeObserver(() => setMapBox({ width: el.clientWidth, height: el.clientHeight }))
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

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

  // 핀은 약도가 있고 좌표가 설정된 부스만 (mapX·mapY null은 목록에만 — 명세 E1)
  const imageRect = map ? containRect(mapBox.width, mapBox.height, map.width, map.height) : null
  const pins =
    map && imageRect
      ? booths
          .filter((b) => b.mapX != null && b.mapY != null)
          .map((b) => ({ booth: b, ...pinPosition(b.mapX!, b.mapY!, imageRect) }))
      : []

  return (
    <div className="relative h-screen w-full overflow-clip bg-white">
      {/* 지도 — 접힘: 화면 전체 / 펼침: 상단 337px. E2 약도를 contain으로 그리고 그 위에 E1 핀을 얹는다 */}
      <div
        ref={mapContainerRef}
        className={`absolute inset-x-0 top-0 bg-neutral-300 transition-[height] duration-300 ease-out ${
          isListOpen ? 'h-[337px]' : 'h-full'
        }`}
      >
        {map && (
          <img
            src={assetUrl(map.imageUrl)}
            alt="행사장 약도"
            draggable={false}
            className="h-full w-full object-contain"
          />
        )}
        {map === null && (
          <p className="absolute inset-x-0 top-1/3 text-center text-body-2 text-neutral-500">
            약도가 아직 등록되지 않았어요. 아래 목록에서 부스를 확인해주세요.
          </p>
        )}
        {pins.map(({ booth, x, y }) => {
          const crowd = crowdLevel(booth.tables.total, booth.tables.empty)
          return (
            <div
              key={booth.boothId}
              className="pointer-events-none absolute flex -translate-x-1/2 -translate-y-full flex-col items-center"
              style={{ left: x, top: y }}
              aria-label={`${booth.name} ${booth.isOpen ? crowd : '주문 마감'}`}
            >
              <span className="rounded-md bg-white/90 px-1.5 py-0.5 text-caption whitespace-nowrap text-black shadow">
                {booth.name}
              </span>
              <span
                className={`mt-0.5 h-3 w-3 rounded-full border-2 border-white shadow ${
                  booth.isOpen ? CROWD_PIN_CLASS[crowd] : 'bg-neutral-400'
                }`}
              />
            </div>
          )
        })}
      </div>

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

        {/* 분류 필터 — E1 ?category=. 시트 안 목록 위에 칩 한 줄 */}
        <div className="absolute inset-x-6 top-14 flex gap-2 overflow-x-auto">
          {CATEGORY_FILTERS.map((filter) => (
            <button
              key={filter.label}
              type="button"
              onClick={() => setCategory(filter.value)}
              className={`h-8 shrink-0 rounded-3xl px-3 text-body-3 ${
                category === filter.value ? 'bg-black text-white' : 'bg-neutral-50 text-neutral-600'
              }`}
            >
              {filter.label}
            </button>
          ))}
        </div>

        <ul className="absolute inset-x-6 top-[100px] bottom-4 flex flex-col gap-5 overflow-y-auto">
          {boothsError && <li className="text-body-3 text-red-600">{boothsError}</li>}
          {booths.map((booth) => (
            // onSelect 미전달: 부스 상세 화면은 만들지 않는다(명세 "홈 화면 구성") — 클릭 가능한 것처럼 보이지 않게 둔다
            <BoothCard key={booth.boothId} booth={booth} />
          ))}
          {booths.length === 0 && !boothsError && (
            <li className="py-10 text-center text-body-2 text-neutral-400">
              {category ? '이 분류의 부스가 없어요.' : '등록된 부스가 없어요.'}
            </li>
          )}
          {/* 좌석 수는 폴링 시점 값(서버 캐시 10초) — 명세 E1 "현재 기준" 문구 권장 */}
          {booths.length > 0 && (
            <li className="pb-2 text-center text-caption text-neutral-400">현재 기준 · 약 10초마다 갱신</li>
          )}
        </ul>
      </div>
    </div>
  )
}
