import { useCallback, useState } from 'react'
import { readStoredJson, writeStored } from './safeStorage'

/**
 * 진행 중 주문의 "나간 메뉴" 체크 — 감튀·치킨·콜라처럼 한 주문이 여러 번에 나눠 나갈 때 뭐가 남았는지 보려고 쓴다.
 *
 * 서버에 저장하지 않고 이 기기(브라우저)에만 남긴다 — 운영자 손 체크용 메모라 주문 상태·정산과 무관하고,
 * 5초 폴링이 카드를 다시 그려도, 새로고침해도 유지되면 충분하다. 다른 기기에는 보이지 않는다.
 *
 * 저장 형태: { [orderId]: { at: 마지막 변경 ms, ids: itemId[] } }. 오래된 주문 기록이 쌓이지 않게
 * 쓸 때마다 하루 넘은 항목을 지운다(영업일 하나 동안만 의미가 있다).
 */
const STORAGE_KEY = 'boothlock.servedItems'
const KEEP_MS = 24 * 60 * 60 * 1000

type ServedMap = Record<string, { at: number; ids: number[] }>

function readAll(): ServedMap {
  const value = readStoredJson<ServedMap>(STORAGE_KEY)
  return value && typeof value === 'object' ? value : {}
}

export function readServedItemIds(orderId: number): ReadonlySet<number> {
  const entry = readAll()[String(orderId)]
  return new Set(Array.isArray(entry?.ids) ? entry.ids : [])
}

export function saveServedItemIds(orderId: number, ids: ReadonlySet<number>, now = Date.now()) {
  const all = readAll()
  for (const [key, entry] of Object.entries(all)) {
    if (!entry || now - entry.at > KEEP_MS) delete all[key]
  }
  if (ids.size === 0) delete all[String(orderId)]
  else all[String(orderId)] = { at: now, ids: [...ids] }
  writeStored(STORAGE_KEY, JSON.stringify(all))
}

/** 카드 하나의 체크 상태 — 처음 그릴 때 저장값을 읽고, 누를 때마다 저장한다 */
export function useServedItems(orderId: number) {
  const [served, setServed] = useState<ReadonlySet<number>>(() => readServedItemIds(orderId))
  const toggle = useCallback(
    (itemId: number) => {
      setServed((prev) => {
        const next = new Set(prev)
        if (next.has(itemId)) next.delete(itemId)
        else next.add(itemId)
        saveServedItemIds(orderId, next)
        return next
      })
    },
    [orderId],
  )
  return { served, toggle }
}
