import { formatClockTime, isLongWait } from '../lib/time'
import { displayTableLabel } from '../lib/tableLabel'
import type { TableStatusInfo } from '../types/table'

type GridDraft = { row: string; col: string }

type TableGridCardProps = {
  table: TableStatusInfo
  editMode: boolean
  onClick: () => void
  /** 편집 모드에서 행/열 입력값(문자열, 미확정) — 편집 모드가 아니면 불필요 */
  gridDraft?: GridDraft
  onGridDraftChange?: (draft: GridDraft) => void
  /** 경과시간 색상 판정 기준 시각(ms) — 첫 주문 후 2시간을 넘기면 시각 텍스트가 빨강(#105) */
  now: number
}

export default function TableGridCard({ table, editMode, onClick, gridDraft, onGridDraftChange, now }: TableGridCardProps) {
  const longWait = isLongWait(table.firstOrderAt, now)
  return (
    <div className="w-[200px]">
      {editMode && onGridDraftChange && (
        <div className="mb-2 flex items-center gap-2">
          <input
            type="number"
            min={1}
            max={50}
            placeholder="행"
            aria-label={`${displayTableLabel(table.label)} 행 번호`}
            value={gridDraft?.row ?? ''}
            onChange={(e) => onGridDraftChange({ row: e.target.value, col: gridDraft?.col ?? '' })}
            className="w-16 rounded-lg border border-neutral-300 px-2 py-1 text-center text-sm"
          />
          <span className="text-neutral-400">행</span>
          <input
            type="number"
            min={1}
            max={50}
            placeholder="열"
            aria-label={`${displayTableLabel(table.label)} 열 번호`}
            value={gridDraft?.col ?? ''}
            onChange={(e) => onGridDraftChange({ row: gridDraft?.row ?? '', col: e.target.value })}
            className="w-16 rounded-lg border border-neutral-300 px-2 py-1 text-center text-sm"
          />
          <span className="text-neutral-400">열</span>
        </div>
      )}
      <button
        type="button"
        onClick={onClick}
        className="flex min-h-[240px] w-[200px] flex-col rounded-xl border border-neutral-200 bg-neutral-50 p-4 text-left"
      >
        <div className="flex items-start justify-between">
          <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
            {displayTableLabel(table.label)}
          </span>
          {/* QR을 처음 찍은 시각(session.startedAt)이 아니라 지금 손님의 첫 주문 시각 — 주문 전에는 표시하지 않는다.
              2시간을 넘기면 색만 빨강으로 바꾼다 — 칸 배경은 그대로, 고객 비노출, 자동 조치 없음 */}
          {table.firstOrderAt && (
            <span
              className={`text-sm leading-[1.5] tracking-[-0.04em] ${longWait ? 'text-red-600' : 'text-neutral-900'}`}
            >
              {formatClockTime(table.firstOrderAt)}
            </span>
          )}
        </div>

        <div className="mt-4 flex flex-1 flex-col gap-1">
          {table.orderItems.length === 0 ? (
            <span className="text-base text-neutral-300">빈 테이블</span>
          ) : (
            table.orderItems.map((item) => (
              <div key={item.menuName} className="flex items-center justify-between text-base text-neutral-900">
                <span>{item.menuName}</span>
                <span>{item.qty}</span>
              </div>
            ))
          )}
        </div>

        {table.orderTotal > 0 && (
          <span className="self-end text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
            {table.orderTotal.toLocaleString()} 원
          </span>
        )}
      </button>
    </div>
  )
}
