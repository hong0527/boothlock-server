import { formatElapsedClock } from '../lib/time'
import { tableNumberLabel } from '../lib/tableLabel'
import { tableTierOf, type TableTier } from '../lib/tableTier'
import type { TableStatusInfo } from '../types/table'

/** 그립 아이콘(6점) — Figma 686:2223, 편집 모드에서 카드를 드래그로 옮기는 손잡이 */
function GripDots({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 16 8" className={className} fill="currentColor">
      {[0, 6, 12].map((x) => (
        <g key={x}>
          <circle cx={x + 2} cy={2} r={1.4} />
          <circle cx={x + 2} cy={6} r={1.4} />
        </g>
      ))}
    </svg>
  )
}

type TableGridCardProps = {
  table: TableStatusInfo
  editMode: boolean
  onClick: () => void
  now: number
  /** 편집 모드 전용 — 그립을 눌러 드래그를 시작한다 */
  onDragStart?: (e: React.PointerEvent) => void
  /** 지금 이 카드가 드래그되어 옮겨지는 중(원래 자리는 흐리게 비워 보여준다) */
  dragging?: boolean
  /** 일괄 삭제 선택 모드 — 켜지면 배색·경과시간·그립을 다 무시하고 흰 배경(선택 시 빨강)에 번호만 보여준다 */
  selectMode?: boolean
  selected?: boolean
}

const TIER_CARD_CLASS: Record<TableTier, string> = {
  empty: 'border-neutral-200 bg-neutral-50 text-neutral-900',
  seated: 'border-primary-100 bg-neutral-50 text-neutral-900',
  longWait: 'border-transparent bg-primary-100 text-white',
}

/**
 * 테이블-홈 카드 (Figma 672:1247) — 정사각형, "테이블" 글자 없이 번호+경과시간만.
 * 자릿세 등 항목 상세는 카드가 아니라 눌렀을 때 여는 결제 모달(PaymentModal)에서 본다 — 카드는
 * 한눈에 보는 상태 표시 전용(9/23 피드백 "2단계 구조").
 */
export default function TableGridCard({
  table,
  editMode,
  onClick,
  now,
  onDragStart,
  dragging,
  selectMode,
  selected,
}: TableGridCardProps) {
  const tier = tableTierOf(table, now)
  const cardClass = selectMode
    ? selected
      ? 'border-transparent bg-red-500 text-white'
      : 'border-neutral-200 bg-neutral-50 text-neutral-900'
    : TIER_CARD_CLASS[tier]

  return (
    // 바깥 상자는 항상 96×96(lib/gridDrag.ts GRID_CARD_SIZE) — 그립은 이 상자 밖 위로 겹쳐 그려서
    // 그리드 절대좌표 배치(TableHomePage)가 그립 높이만큼 밀리지 않게 한다
    <div className={`relative h-24 w-24 ${dragging ? 'opacity-30' : ''}`}>
      {editMode && !selectMode && (
        <button
          type="button"
          onPointerDown={onDragStart}
          aria-label={`${tableNumberLabel(table.label)}번 테이블 옮기기`}
          className="absolute -top-5 left-1/2 -translate-x-1/2 touch-none rounded p-1 text-neutral-400 active:text-neutral-600"
        >
          <GripDots className="h-2 w-4" />
        </button>
      )}
      <button
        type="button"
        onClick={onClick}
        disabled={editMode && !selectMode}
        className={`flex h-24 w-24 flex-col items-center justify-center gap-0.5 rounded-2xl border-2 text-center ${cardClass}`}
      >
        <span className="text-2xl leading-none font-semibold tracking-[-0.04em]">{tableNumberLabel(table.label)}</span>
        {/* 삭제 선택 모드에서는 배색 신호(경과시간)를 아예 안 보여준다 — 번호만으로 고르게 */}
        {!selectMode && table.firstOrderAt && (
          <span className="text-xs leading-none tracking-[-0.04em]">{formatElapsedClock(table.firstOrderAt, now)}</span>
        )}
      </button>
    </div>
  )
}
