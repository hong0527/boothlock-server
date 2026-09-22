import { useEffect, useRef, useState } from 'react'
import moreHorizontal from '../assets/icons/more-horizontal.svg'
import { formatClockTime } from '../lib/time'
import { displayTableLabel } from '../lib/tableLabel'
import type { TableStatusInfo } from '../types/table'

type TableGridCardProps = {
  table: TableStatusInfo
  editMode: boolean
  onClick: () => void
  onMove?: (tableId: number, x: number, y: number) => void
  onDragEnd?: (tableId: number, x: number, y: number) => void
  /** 운영자 화면은 태블릿 폭 기준 — 이 값을 넘겨 드래그가 현재 화면 폭 밖으로 나가지 않게 막는다 */
  maxX?: number
}

export default function TableGridCard({
  table,
  editMode,
  onClick,
  onMove,
  onDragEnd,
  maxX,
}: TableGridCardProps) {
  const [isDragging, setIsDragging] = useState(false)
  const stopDragRef = useRef<(() => void) | null>(null)

  // 언마운트(예: 드래그 중 다른 페이지로 이동) 시 window 리스너가 영원히 안 남게 정리
  useEffect(() => {
    return () => stopDragRef.current?.()
  }, [])

  // 자유 배치 드래그 — Pointer Events로 직접 구현 (네이티브 HTML5 draggable은 터치에서 동작 안 함)
  const handlePointerDown = (e: React.PointerEvent) => {
    const startPointerX = e.clientX
    const startPointerY = e.clientY
    const startX = table.posX ?? 0
    const startY = table.posY ?? 0
    setIsDragging(true)
    let lastX = startX
    let lastY = startY

    const handlePointerMove = (moveEvent: PointerEvent) => {
      const dx = moveEvent.clientX - startPointerX
      const dy = moveEvent.clientY - startPointerY
      lastX = Math.max(0, Math.min(maxX ?? Infinity, startX + dx))
      lastY = Math.max(0, startY + dy)
      onMove?.(table.id, lastX, lastY)
    }

    // 터치에서는 스크롤/시스템 제스처가 끼어들면 pointerup 대신 pointercancel이 온다 — 둘 다 정리해야 함
    const stopDrag = () => {
      setIsDragging(false)
      window.removeEventListener('pointermove', handlePointerMove)
      window.removeEventListener('pointerup', stopDrag)
      window.removeEventListener('pointercancel', stopDrag)
      stopDragRef.current = null
      onDragEnd?.(table.id, lastX, lastY)
    }

    window.addEventListener('pointermove', handlePointerMove)
    window.addEventListener('pointerup', stopDrag)
    window.addEventListener('pointercancel', stopDrag)
    stopDragRef.current = stopDrag
  }

  return (
    // 카드 자체 위치는 항상 (table.posX, table.posY) 고정 — 핸들은 카드 바깥 위쪽에 절대위치로 얹는다
    <div
      className={`absolute w-[200px] ${isDragging ? 'z-10' : 'z-0'}`}
      style={{ left: table.posX ?? 0, top: table.posY ?? 0 }}
    >
      {editMode && (
        <button
          type="button"
          onPointerDown={handlePointerDown}
          aria-label="테이블 위치 옮기기"
          className="absolute -top-8 left-1/2 -translate-x-1/2 cursor-grab touch-none active:cursor-grabbing"
        >
          <img src={moreHorizontal} alt="" draggable={false} className="h-6 w-6" />
        </button>
      )}
      <button
        type="button"
        onClick={onClick}
        className={`flex min-h-[240px] w-[200px] flex-col rounded-xl border bg-neutral-50 p-4 text-left ${
          isDragging ? 'border-neutral-600 shadow-lg' : 'border-neutral-200'
        }`}
      >
        <div className="flex items-start justify-between">
          <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
            {displayTableLabel(table.label)}
          </span>
          {/* QR을 처음 찍은 시각(session.startedAt)이 아니라 지금 손님의 첫 주문 시각 — 주문 전에는 표시하지 않는다 */}
          {table.firstOrderAt && (
            <span className="text-sm leading-[1.5] tracking-[-0.04em] text-neutral-900">
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
