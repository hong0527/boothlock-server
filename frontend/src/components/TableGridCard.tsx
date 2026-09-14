import { useEffect, useRef, useState } from 'react'
import moreHorizontal from '../assets/icons/more-horizontal.svg'
import { formatClockTime } from '../lib/time'
import type { TableOrder } from '../types/table'

type TableGridCardProps = {
  table: TableOrder
  editMode: boolean
  onClick: () => void
  onMove?: (tableId: number, x: number, y: number) => void
}

export default function TableGridCard({ table, editMode, onClick, onMove }: TableGridCardProps) {
  const [isDragging, setIsDragging] = useState(false)
  const stopDragRef = useRef<(() => void) | null>(null)

  // 언마운트(예: 드래그 중 다른 페이지로 이동) 시 window 리스너가 영원히 안 남게 정리
  useEffect(() => {
    return () => stopDragRef.current?.()
  }, [])

  const total = table.items.reduce((sum, item) => sum + item.unitPrice * item.qty, 0)

  // 자유 배치 드래그 — Pointer Events로 직접 구현 (네이티브 HTML5 draggable은 터치에서 동작 안 함)
  const handlePointerDown = (e: React.PointerEvent) => {
    const startPointerX = e.clientX
    const startPointerY = e.clientY
    const startX = table.x
    const startY = table.y
    setIsDragging(true)

    const handlePointerMove = (moveEvent: PointerEvent) => {
      const dx = moveEvent.clientX - startPointerX
      const dy = moveEvent.clientY - startPointerY
      onMove?.(table.id, Math.max(0, startX + dx), Math.max(0, startY + dy))
    }

    // 터치에서는 스크롤/시스템 제스처가 끼어들면 pointerup 대신 pointercancel이 온다 — 둘 다 정리해야 함
    const stopDrag = () => {
      setIsDragging(false)
      window.removeEventListener('pointermove', handlePointerMove)
      window.removeEventListener('pointerup', stopDrag)
      window.removeEventListener('pointercancel', stopDrag)
      stopDragRef.current = null
    }

    window.addEventListener('pointermove', handlePointerMove)
    window.addEventListener('pointerup', stopDrag)
    window.addEventListener('pointercancel', stopDrag)
    stopDragRef.current = stopDrag
  }

  return (
    // 카드 자체 위치는 항상 (table.x, table.y) 고정 — 핸들은 카드 바깥 위쪽에 절대위치로 얹는다
    <div
      className={`absolute w-[200px] ${isDragging ? 'z-10' : 'z-0'}`}
      style={{ left: table.x, top: table.y }}
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
            {table.label}
          </span>
          <span className="text-sm leading-[1.5] tracking-[-0.04em] text-neutral-900">
            {formatClockTime(table.startedAt)}
          </span>
        </div>

        <ul className="mt-4 flex flex-1 flex-col gap-2">
          {table.items.map((item) => (
            <li
              key={item.id}
              className="flex items-center justify-between text-base leading-[1.5] tracking-[-0.04em] text-neutral-900"
            >
              <span>{item.name}</span>
              <span>{item.qty}</span>
            </li>
          ))}
          {table.items.length === 0 && <li className="text-neutral-300">빈 테이블</li>}
        </ul>

        {total > 0 && (
          <span className="mt-2 self-end text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
            {total.toLocaleString()} 원
          </span>
        )}
      </button>
    </div>
  )
}
